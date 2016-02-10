/*
 * 
 * 
 * Copyright 2016 Rachid Boudjelida <rachidboudjelida@gmail.com>
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
package deodex.controlers;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import javax.swing.JProgressBar;

import deodex.R;
import deodex.S;
import deodex.obj.ApkObj;
import deodex.tools.Deodexer;
import deodex.tools.FilesUtils;
import deodex.tools.Logger;
import deodex.tools.Zip;
import deodex.tools.ZipTools;

public class ApkWorker implements Runnable {

	/**
	 * constructor the argument is a list of the APk objectes to be deodexed
	 * (lolipop and above)
	 * 
	 * @param apkFoldersList
	 */
	ArrayList<File> apkList;
	LoggerPan logPan;
	File tmpFolder;
	boolean doSign;
	boolean doZipalign;
	public JProgressBar progressBar;
	ThreadWatcher threadWatcher;

	public ApkWorker(ArrayList<File> apkFoldersList, LoggerPan logPan, File tmpFolder, boolean doSign,
			boolean doZipalign) {
		apkList = apkFoldersList;
		this.logPan = logPan;
		this.tmpFolder = tmpFolder;
		this.doSign = doSign;
		this.doZipalign = doZipalign;

		progressBar = new JProgressBar();
		progressBar.setMinimum(0);
		if(apkList != null)
			progressBar.setMaximum(apkList.size() <= 0 ? 2: apkList.size());
		else
			progressBar.setMaximum(1);
		progressBar.setStringPainted(true);
	}

	/**
	 * @param threadWatcher
	 *            the threadWatcher to set
	 */
	public void addThreadWatcher(ThreadWatcher threadWatcher) {
		this.threadWatcher = threadWatcher;
	}

	private boolean deodexApk(File apkFolder) {
		ApkObj apk = new ApkObj(apkFolder);

		boolean copyStatus = apk.copyNeededFilesToTempFolder(tmpFolder);
		if (!copyStatus) { // returns
			logPan.addLog(R.getString(S.LOG_WARNING) + " [" + apk.getOrigApk().getName() + "]"
					+ R.getString("log.copy.to.tmp.failed"));
			return false;
		}

		boolean extraxtStatus = false;
		try {
			extraxtStatus = ZipTools.extractOdex(apk.getTempOdex());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if (!extraxtStatus) {
			logPan.addLog(R.getString(S.LOG_WARNING) + " [" + apk.getOrigApk().getName() + "]"
					+ R.getString("log.extract.to.tmp.failed"));
			FilesUtils.deleteRecursively(apk.getTempApk().getParentFile());
			return false;
		}

		boolean dexStatus = Deodexer.deodexApk(apk.getTempOdex(), apk.getTempDex());
		if (!dexStatus) {
			dexStatus = Deodexer.deodexApkFailSafe(apk.getTempOdex(), apk.getTempDex());
			if (!dexStatus) {
				logPan.addLog(R.getString(S.LOG_WARNING) + " [" + apk.getOrigApk().getName() + "]"
						+ R.getString("log.deodex.failed"));
				FilesUtils.deleteRecursively(apk.getTempApk().getParentFile());
				return false;
			}
		}

		boolean rename = FilesUtils.copyFile(apk.getTempDex(), apk.getTempClasses1());
		if (apk.getTempDex2().exists()) {
			rename = rename && FilesUtils.copyFile(apk.getTempDex2(), apk.getTempClasses2());
		}
		if (!rename) {
			logPan.addLog(R.getString(S.LOG_WARNING) + " [" + apk.getOrigApk().getName() + "]"
					+ R.getString("log.classes.failed"));
			FilesUtils.deleteRecursively(apk.getTempApk().getParentFile());
			return false;

		}

		ArrayList<File> classesFiles = new ArrayList<File>();
		classesFiles.add(apk.getTempClasses1());
		if (apk.getTempClasses2().exists())
			classesFiles.add(apk.getTempClasses2());
		boolean addClassesToApkStatus = false;
		try {
			addClassesToApkStatus = Zip.addFilesToExistingZip(apk.getTempApk(), classesFiles);
		} catch (IOException e) {
			e.printStackTrace();
		}
		if (!addClassesToApkStatus) {
			logPan.addLog(R.getString(S.LOG_WARNING) + " [" + apk.getOrigApk().getName() + "]"
					+ R.getString("log.add.classes.failed"));
			// FilesUtils.deleteRecursively(apk.getTempApk().getParentFile());
			return false;
		}

		if (this.doSign) {
			// TODO sign !
			try {
				Logger.logToStdIO(Deodexer.signApk(apk.getTempApk(), apk.getTempApkSigned()) + " sign status for "
						+ apk.getOrigApk().getName());
			} catch (IOException | InterruptedException e) {
				FilesUtils.copyFile(apk.getTempApk(), apk.getTempApkSigned());
			}
		} else {
			FilesUtils.copyFile(apk.getTempApk(), apk.getTempApkSigned());

		}
		if (this.doZipalign) {
			try {
				Zip.zipAlignAPk(apk.getTempApkSigned(), apk.getTempApkZipalign());
			} catch (IOException | InterruptedException e) {
				FilesUtils.copyFile(apk.getTempApkSigned(), apk.getTempApkZipalign());
			}
		} else {
			FilesUtils.copyFile(apk.getTempApkSigned(), apk.getTempApkZipalign());
		}

		// the process is successful now copy and clean !
		FilesUtils.copyFile(apk.getTempApkZipalign(), apk.getOrigApk());

		// delete the arch folder clearlly we dont need it any more
		// FIXME : clean all odexFiles in the folder
		FilesUtils.deleteFiles(FilesUtils.searchrecursively(apk.getFolder(), S.ODEX_EXT));
		FilesUtils.deleteFiles(FilesUtils.searchrecursively(apk.getFolder(), S.COMP_ODEX_EXT));
		FilesUtils.deleteUmptyFoldersInFolder(apk.getFolder());

		FilesUtils.deleteRecursively(apk.getTempApkZipalign().getParentFile());

		return true;
	}

	/**
	 * @return the progressBar
	 */
	public JProgressBar getProgressBar() {
		return progressBar;
	}

	/**
	 * @return the threadWatcher
	 */
	public ThreadWatcher getThreadWatcher() {
		return threadWatcher;
	}

	@Override
	public void run() {
		// TODO Auto-generated method stub
		if (apkList != null && apkList.size() > 0) {
			for (File apk : apkList) {

				boolean sucess = deodexApk(apk);
				if (!sucess) {
					logPan.addLog("[" + new ApkObj(apk).getOrigApk().getName() + "]" + R.getString(S.LOG_FAIL));
				} else {
					logPan.addLog("[" + new ApkObj(apk).getOrigApk().getName() + "]" + R.getString(S.LOG_SUCCESS));
				}
				progressBar.setValue(progressBar.getValue()+1);
				progressBar.setString(R.getString("progress.apks") + " (" + progressBar.getValue() + "/"
						+ progressBar.getMaximum() + ")");
				threadWatcher.updateProgress();
			}
		}
		
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		FilesUtils.deleteRecursively(tmpFolder);
		progressBar.setValue(progressBar.getMaximum());
		progressBar.setString(R.getString("progress.done"));
		this.threadWatcher.done(this);
	}

	/**
	 * @param progressBar
	 *            the progressBar to set
	 */
	public void setProgressBar(JProgressBar progressBar) {
		this.progressBar = progressBar;
	}
}
