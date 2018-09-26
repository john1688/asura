package asura.core.job

import asura.common.util.FutureUtils.RichFuture
import asura.common.util.LogUtils
import asura.core.es.model.{BaseIndex, JobReport}
import asura.core.es.service.{JobReportService, JobService, ReportNotifyService}
import asura.core.job.actor.{JobFinished, JobRunning, SchedulerActor}
import com.typesafe.scalalogging.Logger
import org.quartz.{Job, JobExecutionContext}

/** job execution wrap */
abstract class AbstractJob extends Job {

  val logger = Logger(getClass)
  var jobExecutionContext: JobExecutionContext = null

  override def execute(context: JobExecutionContext) = {
    this.jobExecutionContext = context
    val execDesc = beforeRun(context)
    try {
      val job = execDesc.job
      SchedulerActor.statusMonitor ! JobRunning(job.scheduler, job.group, job.summary)
      run(execDesc)
    } catch {
      case t: Throwable =>
        execDesc.status(JobExecDesc.STATUS_FAIL).errorMsg(t.getMessage)
    } finally {
      afterRun(execDesc)
    }
  }

  private def beforeRun(context: JobExecutionContext): JobExecDesc = {
    val jobKey = context.getJobDetail.getKey
    val jobId = jobKey.getName
    val job = JobService.geJobById(jobId).await
    JobExecDesc.from(jobId, job, JobReport.TYPE_QUARTZ, null, BaseIndex.CREATOR_QUARTZ).await
  }

  private def afterRun(jobExecDesc: JobExecDesc): Unit = {
    jobExecDesc.prepareEnd()
    saveAndNotifyJobResult(jobExecDesc)
  }

  private def saveAndNotifyJobResult(execDesc: JobExecDesc): Unit = {
    val job = execDesc.job
    val report = execDesc.report
    SchedulerActor.statusMonitor ! JobFinished(job.scheduler, job.group, job.summary, execDesc.report)
    try {
      JobReportService.indexReport(execDesc.reportId, report).await
      ReportNotifyService.notifySubscribers(execDesc, execDesc.reportId).await
    } catch {
      case t: Throwable =>
        logger.warn(LogUtils.stackTraceToString(t))
    }
  }

  def pauseSelf(jobExecDesc: JobExecDesc): Unit = {
    this.jobExecutionContext.getScheduler.pauseJob(jobExecutionContext.getJobDetail.getKey)
  }

  /** Main business what job do, any job should implements this method.
    * The default job status is success, you should set job status and error message if
    * the job should not be successful.
    * Other metrics like time cost the job implements need not to care.
    */
  def run(execDesc: JobExecDesc)
}
