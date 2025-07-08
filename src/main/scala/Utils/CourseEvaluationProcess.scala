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
          details = s"CourseID: ${courseID}, Details: ${details}"
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
  
    if (studentToken == null || studentToken.isEmpty) {
      logger.info(s"[validateStudentToken] 传入的 studentToken 为空或无效")
      IO.pure(None)
    } else {
      for {
        // Step 1: 验证 Token 有效性
        _ <- IO(logger.info(s"[validateStudentToken] 验证 studentToken 的有效性: ${studentToken}"))
        tokenValid <- VerifyTokenValidityMessage(studentToken).send
        _ <- if (!tokenValid) IO(logger.info(s"[validateStudentToken] studentToken ${studentToken} 无效")) else IO.unit
  
        result <- if (!tokenValid) {
          IO.pure(None)
        } else {
          for {
            // Step 2: 解析 Token 获取学生信息
            _ <- IO(logger.info(s"[validateStudentToken] studentToken ${studentToken} 验证通过，开始解析学生信息"))
            safeUserInfoOpt <- QuerySafeUserInfoByTokenMessage(studentToken).send
            _ <- if (safeUserInfoOpt.isEmpty) IO(logger.info(s"[validateStudentToken] 无法解析 studentToken ${studentToken} 为 SafeUserInfo")) else IO.unit
  
            result <- safeUserInfoOpt match {
              case None => IO.pure(None)
              case Some(safeUserInfo) =>
                if (safeUserInfo.role != UserRole.Student) {
                  IO {
                    logger.info(
                      s"[validateStudentToken] SafeUserInfo 角色不是学生：" +
                        s"token=${studentToken}, userID=${safeUserInfo.userID}, role=${safeUserInfo.role}"
                    )
                  }.as(None)
                } else {
                  for {
                    // Step 3: 记录日志
                    _ <- IO(logger.info(s"[validateStudentToken] 开始记录验证日志"))
                    operation <- IO("验证学生Token")
                    currentTime <- IO(DateTime.now())
                    logDetails <- IO(s"操作时间: ${currentTime}, studentToken: ${studentToken}")
                    _ <- recordCourseEvaluationOperationLog(
                      safeUserInfo.userID,
                      operation,
                      courseID = 0, // 此处未指定课程ID，传入0
                      logDetails
                    )
  
                    // Step 4: 返回学生 ID
                    _ <- IO(logger.info(s"[validateStudentToken] 学生Token验证成功，返回学生ID: ${safeUserInfo.userID}"))
                  } yield Some(safeUserInfo.userID)
                }
            }
          } yield result
        }
      } yield result
    }
  }
}
