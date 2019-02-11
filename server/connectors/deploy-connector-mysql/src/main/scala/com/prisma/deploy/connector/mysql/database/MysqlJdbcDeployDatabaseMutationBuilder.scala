package com.prisma.deploy.connector.mysql.database

import com.prisma.connector.shared.jdbc.SlickDatabase
import com.prisma.deploy.connector.jdbc.database.{JdbcDeployDatabaseMutationBuilder, TypeMapper}
import com.prisma.shared.models.FieldBehaviour.IdBehaviour
import com.prisma.shared.models.Manifestations.RelationTable
import com.prisma.shared.models.TypeIdentifier.{ScalarTypeIdentifier, TypeIdentifier}
import com.prisma.shared.models._
import com.prisma.utils.boolean.BooleanUtils
import org.jooq.impl.DSL
import slick.dbio.{DBIOAction => DatabaseAction}

import scala.concurrent.ExecutionContext

case class MySqlJdbcDeployDatabaseMutationBuilder(
    slickDatabase: SlickDatabase,
    typeMapper: TypeMapper
)(implicit val ec: ExecutionContext)
    extends JdbcDeployDatabaseMutationBuilder
    with BooleanUtils {

  import slickDatabase.profile.api._

  override def createSchema(projectId: String): DBIO[_] = {
    sqlu"CREATE SCHEMA #${qualify(projectId)}"
  }

  override def truncateProjectTables(project: Project): DBIO[_] = {
    val listTableNames: List[String] = project.models.flatMap { model =>
      model.fields.collect { case field if field.isScalar && field.isList => s"${model.dbName}_${field.dbName}" }
    }

    val tables = Vector("_RelayId") ++ project.models.map(_.dbName) ++ project.relations.map(_.relationTableName) ++ listTableNames
    val queries = tables.map(tableName => {
      changeDatabaseQueryToDBIO(sql.truncate(DSL.name(project.dbName, tableName)))()
    })

    DBIO.seq(sqlu"set foreign_key_checks=0" +: queries :+ sqlu"set foreign_key_checks=1": _*)
  }

  override def deleteProjectDatabase(projectId: String) = {
    sqlu"DROP DATABASE IF EXISTS #${qualify(projectId)}"
  }

  override def createModelTable(project: Project, model: Model): DBIO[_] = {
    val idField              = model.idField_!
    val idFieldSQL           = typeMapper.rawSQLForField(idField)
    val initialSequenceValue = idField.behaviour.collect { case IdBehaviour(_, Some(seq)) => seq.initialValue }

    sqlu"""CREATE TABLE #${qualify(project.dbName, model.dbName)} (
           #$idFieldSQL,
           PRIMARY KEY (#${qualify(idField.dbName)}))
           #${if (idField.isAutoGeneratedByDb && initialSequenceValue.isDefined) s"auto_increment = ${initialSequenceValue.get}" else ""}
           DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"""
  }

  override def createScalarListTable(project: Project, model: Model, fieldName: String, typeIdentifier: ScalarTypeIdentifier): DBIO[_] = {
    val indexSize = indexSizeForSQLType(typeMapper.rawSqlTypeForScalarTypeIdentifier(typeIdentifier))
    val nodeIdSql = typeMapper.rawSQLFromParts("nodeId", isRequired = true, TypeIdentifier.Cuid)
    val valueSql  = typeMapper.rawSQLFromParts("value", isRequired = true, typeIdentifier)

    sqlu"""CREATE TABLE #${qualify(project.dbName, s"${model.dbName}_$fieldName")} (
           #$nodeIdSql,
           `position` INT(4) NOT NULL,
           #$valueSql,
           PRIMARY KEY (`nodeId`, `position`),
           INDEX `value` (`value`#$indexSize ASC),
           FOREIGN KEY (`nodeId`) REFERENCES #${qualify(project.dbName, model.dbName)} (#${qualify(model.idField_!.dbName)}) ON DELETE CASCADE)
           DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"""
  }

  override def createRelationTable(project: Project, relation: Relation): DBIO[_] = {
    val relationTableName = relation.relationTableName
    val modelA            = relation.modelA
    val modelB            = relation.modelB
    val modelAColumn      = relation.modelAColumn
    val modelBColumn      = relation.modelBColumn
    val aColSql           = typeMapper.rawSQLFromParts(modelAColumn, isRequired = true, modelA.idField_!.typeIdentifier)
    val bColSql           = typeMapper.rawSQLFromParts(modelBColumn, isRequired = true, modelB.idField_!.typeIdentifier)

    // we do not create an index on A because queries for the A column can be satisfied with the combined index as well

    def legacyTableCreate(idColumn: String) = {
      val idSql = typeMapper.rawSQLFromParts(idColumn, isRequired = true, TypeIdentifier.Cuid)
      sqlu"""
         CREATE TABLE #${qualify(project.dbName, relationTableName)} (
           #$idSql,
           PRIMARY KEY (`#$idColumn`),
           #$aColSql, 
           #$bColSql,
           INDEX `#$modelBColumn` (`#$modelBColumn` ASC),
           UNIQUE INDEX `#${relation.name}_AB_unique` (`#$modelAColumn` ASC, `#$modelBColumn` ASC),
           FOREIGN KEY (#$modelAColumn) REFERENCES #${qualify(project.dbName, modelA.dbName)}(#${qualify(modelA.dbNameOfIdField_!)}) ON DELETE CASCADE,
           FOREIGN KEY (#$modelBColumn) REFERENCES #${qualify(project.dbName, modelB.dbName)}(#${qualify(modelB.dbNameOfIdField_!)}) ON DELETE CASCADE)
           DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
      """
    }

    val modernTableCreate = sqlu"""
         CREATE TABLE #${qualify(project.dbName, relationTableName)} (
           #$aColSql,
           #$bColSql,
           INDEX `#$modelBColumn` (`#$modelBColumn` ASC),
           UNIQUE INDEX `#${relation.name}_AB_unique` (`#$modelAColumn` ASC, `#$modelBColumn` ASC),
           FOREIGN KEY (#$modelAColumn) REFERENCES #${qualify(project.dbName, modelA.dbName)}(#${qualify(modelA.dbNameOfIdField_!)}) ON DELETE CASCADE,
           FOREIGN KEY (#$modelBColumn) REFERENCES #${qualify(project.dbName, modelB.dbName)}(#${qualify(modelB.dbNameOfIdField_!)}) ON DELETE CASCADE)
           DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
      """

    relation.manifestation match {
      case RelationTable(_, _, _, Some(idColumn)) => legacyTableCreate(idColumn)
      case _                                      => modernTableCreate
    }
  }

  override def createRelationColumn(project: Project, model: Model, references: Model, column: String): DBIO[_] = {
    val colSql = typeMapper.rawSQLFromParts(column, isRequired = false, references.idField_!.typeIdentifier)
    sqlu"""ALTER TABLE #${qualify(project.dbName, model.dbName)}
          ADD COLUMN #$colSql,
          ADD FOREIGN KEY (#${qualify(column)}) REFERENCES #${qualify(project.dbName, references.dbName)}(#${qualify(references.idField_!.dbName)}) ON DELETE CASCADE;
        """
  }

  override def deleteRelationColumn(project: Project, model: Model, references: Model, column: String): DBIO[_] = {
    for {
      namesOfForeignKey <- getNamesOfForeignKeyConstraints(project, model, column)
      _                 <- sqlu"""ALTER TABLE #${qualify(project.dbName, model.dbName)} DROP FOREIGN KEY `#${namesOfForeignKey.head}`;"""
      _                 <- sqlu"""ALTER TABLE #${qualify(project.dbName, model.dbName)} DROP COLUMN `#$column`;"""
    } yield ()
  }

  private def getNamesOfForeignKeyConstraints(project: Project, model: Model, column: String): DatabaseAction[Vector[String], NoStream, Effect] = {
    for {
      result <- sql"""
            SELECT
              CONSTRAINT_NAME
            FROM
              INFORMATION_SCHEMA.KEY_COLUMN_USAGE
            WHERE
              REFERENCED_TABLE_SCHEMA = '#${project.dbName}' AND
              TABLE_NAME = '#${model.dbName}' AND
              COLUMN_NAME = '#$column';
          """.as[String]
    } yield result
  }

  override def createColumn(project: Project, field: ScalarField): DBIO[_] = {
    val newColSql = typeMapper.rawSQLForField(field)
    sqlu"""ALTER TABLE #${qualify(project.dbName, field.model.dbName)} ADD COLUMN #$newColSql, ALGORITHM = INPLACE"""
  }

  override def deleteColumn(project: Project, tableName: String, columnName: String, model: Option[Model]) = {
    sqlu"""ALTER TABLE #${qualify(project.dbName, tableName)} DROP COLUMN #${qualify(columnName)}"""
  }

  override def updateColumn(project: Project,
                            field: ScalarField,
                            oldTableName: String,
                            oldColumnName: String,
                            oldTypeIdentifier: ScalarTypeIdentifier): DBIO[_] = {
    val typeChange = if (oldTypeIdentifier != field.typeIdentifier) {
      sqlu"UPDATE #${qualify(project.dbName, oldTableName)} SET #${qualify(oldColumnName)} = null"
    } else { DBIO.successful(()) }

    val newColSql = typeMapper.rawSQLForField(field)

    DBIO.seq(typeChange, sqlu"ALTER TABLE #${qualify(project.dbName, oldTableName)} CHANGE COLUMN #${qualify(oldColumnName)} #$newColSql")
  }

  def indexSizeForSQLType(sql: String): String = sql match {
    case x if x.startsWith("text") | x.startsWith("mediumtext") => "(191)"
    case _                                                      => ""
  }

  override def addUniqueConstraint(project: Project, field: Field): DBIO[_] = {
    val sqlType   = typeMapper.rawSqlTypeForScalarTypeIdentifier(field.typeIdentifier)
    val indexSize = indexSizeForSQLType(sqlType)

    sqlu"ALTER TABLE #${qualify(project.dbName, field.model.dbName)} ADD UNIQUE INDEX #${qualify(s"${field.dbName}_UNIQUE")}(#${qualify(field.dbName)}#$indexSize ASC)"
  }

  override def removeIndex(project: Project, tableName: String, indexName: String): DBIO[_] = {
    sqlu"ALTER TABLE #${qualify(project.dbName, tableName)} DROP INDEX #${qualify(indexName)}"
  }

  override def renameTable(project: Project, oldTableName: String, newTableName: String): DBIO[_] = {
    if (oldTableName != newTableName) {
      sqlu"""ALTER TABLE #${qualify(project.dbName, oldTableName)} RENAME TO #${qualify(project.dbName, newTableName)}"""
    } else {
      DatabaseAction.successful(())
    }
  }

  //Here this is only used for relationtables
  override def renameColumn(project: Project, tableName: String, oldColumnName: String, newColumnName: String, typeIdentifier: TypeIdentifier): DBIO[_] = {
    val newColSql = typeMapper.rawSQLFromParts(newColumnName, isRequired = true, typeIdentifier)
    sharedRename(project, tableName, oldColumnName, newColumnName, newColSql)
  }

  private def renameColumn(project: Project, tableName: String, oldColumnName: String, field: Field): DBIO[_] = {
    val newColSql = typeMapper.rawSQLFromParts(field.dbName, isRequired = field.isRequired, field.typeIdentifier)
    sharedRename(project, tableName, oldColumnName, field.dbName, newColSql)
  }

  private def sharedRename(project: Project, tableName: String, oldName: String, newName: String, typeString: String) = {
    if (oldName != newName) {
      sqlu"ALTER TABLE #${qualify(project.dbName, tableName)} CHANGE COLUMN #${qualify(oldName)} #$typeString"
    } else {
      DatabaseAction.successful(())
    }
  }
}
