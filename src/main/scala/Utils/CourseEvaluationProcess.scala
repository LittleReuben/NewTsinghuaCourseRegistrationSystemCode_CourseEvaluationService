package Utils

//process plan import 预留标志位，不要删除
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import org.joda.time.DateTime
import Common.DBAPI._
import Common.ServiceUtils.schemaName
import org.slf4j.LoggerFactory
import Objects.SystemLogService.SystemLogEntry
import Common.API.{PlanContext, Planner}
import Common.Object.SqlParameter
import cats.effect.IO
import cats.implicits.*
import Common.Serialize.CustomColumnTypes.{decodeDateTime, encodeDateTime}
import Common.Serialize.CustomColumnTypes.{decodeDateTime,encodeDateTime}
import Common.API.{PlanContext}
import Objects.UserAccountService.SafeUserInfo
import Utils.CourseEvaluationProcess.recordCourseEvaluationOperationLog
import APIs.UserAuthService.VerifyTokenValidityMessage
import APIs.UserAccountService.QuerySafeUserInfoByTokenMessage
import Objects.UserAccountService.UserRole
import cats.implicits._
import Common.DBAPI.{readDBRows}

case object CourseEvaluationProcess {
  private val logger = LoggerFactory.getLogger(getClass)
  //process plan code 预留标志位，不要删除
  
  def recordCourseEvaluationOperationLog(studentID: Int, operation: String, courseID: Int, details: String)(using PlanContext): IO[Unit] = {
  // val logger = LoggerFactory.getLogger("recordCourseEvaluationOperationLog")  // 同文后端处理: logger 统一
  
    for {
      // Step 1: 记录初始日志信息
      _ <- IO(logger.info(s"[recordCourseEvaluationOperationLog] 开始记录日志，studentID: ${studentID}, operation: ${operation}, courseID: ${courseID}, details: ${details}"))
  
      // Step 2: 构造日志记录信息
      timestamp <- IO(DateTime.now())
      _ <- IO(logger.info(s"[recordCourseEvaluationOperationLog] 生成当前时间戳: ${timestamp}"))
  
      logEntry <- IO {
        SystemLogEntry(
          logID = 0, // logID 自动生成，不需赋值
          timestamp = timestamp,
          userID = studentID,
          action = operation,
          details = s"[课程 ID: ${courseID}] ${details}"
        )
      }
      _ <- IO(logger.info(s"[recordCourseEvaluationOperationLog] 构造日志条目完成: ${logEntry}"))
  
      // Step 3: 构造数据库插入SQL语句
      sql <- IO {
        s"""
  INSERT INTO ${schemaName}.system_log_table (timestamp, user_id, action, details)
  VALUES (?, ?, ?, ?)
  """.stripMargin
      }
      _ <- IO(logger.info(s"[recordCourseEvaluationOperationLog] 构造插入SQL完成: ${sql}"))
  
      params <- IO {
        List(
          SqlParameter("DateTime", timestamp.getMillis.toString),
          SqlParameter("Int", studentID.toString),
          SqlParameter("String", operation),
          SqlParameter("String", s"CourseID: ${courseID}, Details: ${details}")
        )
      }
      _ <- IO(logger.info(s"[recordCourseEvaluationOperationLog] 构造插入参数完成: ${params}"))
  
      // Step 4: 插入日志信息到数据库
      _ <- IO(logger.info(s"[recordCourseEvaluationOperationLog] 开始执行数据库插入操作"))
      _ <- writeDB(sql, params)
      _ <- IO(logger.info(s"[recordCourseEvaluationOperationLog] 日志插入操作完成"))
    } yield ()
  }
  
  
  def validateStudentEligibilityForEvaluation(studentID: Int, courseID: Int)(using PlanContext): IO[Boolean] = {
  // val logger = LoggerFactory.getLogger("validateStudentEligibilityForEvaluation")  // 同文后端处理: logger 统一
  
    logger.info(s"[validateStudentEligibilityForEvaluation] 开始验证学生是否有评价资格 (studentID=${studentID}, courseID=${courseID})")
  
    // 定义数据库表名
    val courseParticipationTable = s"${schemaName}.course_participation_history_table"
    val courseSelectionTable = s"${schemaName}.course_selection_table"
  
    // 定义查询 SQL 语句
    val sql =
      s"""
  SELECT COUNT(*) > 0 AS is_eligible
  FROM (
    SELECT student_id, course_id
    FROM ${courseParticipationTable}
    WHERE student_id = ? AND course_id = ?
    UNION
    SELECT student_id, course_id
    FROM ${courseSelectionTable}
    WHERE student_id = ? AND course_id = ?
  ) AS eligibility_check;
      """.stripMargin
  
    logger.info(s"[validateStudentEligibilityForEvaluation] 数据库查询SQL为:\n${sql}")
  
    // 定义 SQL 查询参数
    val parameters = List(
      SqlParameter("Int", studentID.toString),
      SqlParameter("Int", courseID.toString),
      SqlParameter("Int", studentID.toString),
      SqlParameter("Int", courseID.toString)
    )
  
    // 发送查询并解析结果
    for {
      result <- readDBBoolean(sql, parameters)
      _ <- IO(logger.info(s"[validateStudentEligibilityForEvaluation] 验证结果为: ${result}"))
    } yield result
  }
  
  def validateStudentToken(studentToken: String)(using PlanContext): IO[Option[Int]] = {
  // val logger = LoggerFactory.getLogger("validateStudentToken")  // 同文后端处理: logger 统一
  
    logger.info(s"开始验证学生Token: ${studentToken}")
  
    for {
      // Step 1: 验证Token有效性
      isValidToken <- VerifyTokenValidityMessage(studentToken).send
      _ <- IO {
        if (!isValidToken)
          logger.error(s"学生Token验证失败: ${studentToken}无效")
        else
          logger.info(s"学生Token验证有效，继续解析学生信息")
      }
  
      // Step 2: 根据Token获取学生信息
      studentInfoOpt <- if (isValidToken) {
        QuerySafeUserInfoByTokenMessage(studentToken).send
      } else {
        IO.pure(None)
      }
  
      // Step 3: 判断角色并获取学生ID
      studentIDOpt = studentInfoOpt.flatMap { userInfo =>
        // 修复逻辑：直接使用Option字段进行校验和获取
        if (userInfo.role == UserRole.Student) Some(userInfo.userID) else None
      }
  
      _ <- IO {
        if (studentInfoOpt.isEmpty)
          logger.error(s"未能根据Token获取学生信息: ${studentToken}")
        else if (studentIDOpt.isEmpty)
          logger.error(s"Token解析用户并非学生角色: ${studentToken}")
        else
          logger.info(s"学生Token验证通过，学生ID: ${studentIDOpt.get}")
      }
    } yield studentIDOpt
  }
}
