package org.mark.file.downloader;

public interface DownloadProgressListener {

	void onStateChanged(DownloadTaskInfo task, DownloadTaskStatus oldState, DownloadTaskStatus newState);

	void onProgressUpdated(DownloadTaskInfo task, DownloadTaskProgress progress);

	/**
	 * DEAD CODE — {@code DownloadTaskManager.notifyTaskCompleted} is never called. Completion events
	 * reach listeners via {@code onStateChanged(..., COMPLETED)} instead. Retained only to keep the
	 * interface contract; remove together with {@code DownloadTaskManager.notifyTaskCompleted} and
	 * {@code DownloadWebSocketListener.onTaskCompleted} in a future cleanup.
	 */
	@Deprecated
	void onTaskCompleted(DownloadTaskInfo task);

	void onTaskFailed(DownloadTaskInfo task, String error);

	void onTaskPaused(DownloadTaskInfo task);

	void onTaskResumed(DownloadTaskInfo task);
}
