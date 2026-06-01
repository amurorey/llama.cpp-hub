package org.mark.file.downloader;

public interface DownloadProgressListener {

	void onStateChanged(DownloadTaskInfo task, DownloadTaskStatus oldState, DownloadTaskStatus newState);

	void onProgressUpdated(DownloadTaskInfo task, DownloadTaskProgress progress);

	void onTaskFailed(DownloadTaskInfo task, String error);

	void onTaskPaused(DownloadTaskInfo task);

	void onTaskResumed(DownloadTaskInfo task);
}
