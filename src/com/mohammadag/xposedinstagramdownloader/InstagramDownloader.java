package com.mohammadag.xposedinstagramdownloader;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

import java.io.File;
import java.util.ArrayList;

import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class InstagramDownloader implements IXposedHookLoadPackage {

	private CharSequence[] mMenuOptions = null;
	protected Object mCurrentMediaOptionButton;
	private static final String mDownloadString = "Download";
	
	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals("com.instagram.android"))
			return;
		
		/* Hi Facebook team! Obfuscating the package isn't enough */
		Class<?> MediaOptionsButton = findClass("com.instagram.android.feed.a.a.x", lpparam.classLoader);
		findAndHookMethod(MediaOptionsButton, "b", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				CharSequence[] result = (CharSequence[]) param.getResult();
				
				ArrayList<String> array = new ArrayList<String>();
				for (CharSequence sq : result)
					array.add(sq.toString());
				
				if (!array.contains(mDownloadString))
					array.add(mDownloadString);
				CharSequence[] newResult = new CharSequence[array.size()];
				array.toArray(newResult);
				setObjectField(param.thisObject, "j", newResult);
				mMenuOptions = (CharSequence[]) getObjectField(param.thisObject, "j");
				param.setResult(mMenuOptions);
			}
		});
		
		findAndHookMethod(MediaOptionsButton, "a", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				mCurrentMediaOptionButton = param.thisObject;
			}
		});
		
		Class<?> MenuClickListener = findClass("com.instagram.android.feed.a.a.z", lpparam.classLoader);
		findAndHookMethod(MenuClickListener, "onClick", DialogInterface.class, int.class, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				CharSequence localCharSequence = mMenuOptions[(Integer) param.args[1]];
				if (mDownloadString.equals(localCharSequence)) {
					Object mMedia = getObjectField(mCurrentMediaOptionButton, "h");
					Object mMediaType = getObjectField(mMedia, "b");
					Object videoType = getStaticObjectField(mMediaType.getClass(), "b");
					String linkToDownload;
					String filenameExtension;
					String descriptionType;
					if (mMediaType.equals(videoType)) {
						linkToDownload = (String) getObjectField(mMedia, "C");
						filenameExtension = "mp4";
						descriptionType = "video";
					} else {
						linkToDownload = (String) getObjectField(mMedia, "s");
						filenameExtension = "jpg";
						descriptionType = "photo";
					}
					
					Context context = (Context) getObjectField(mCurrentMediaOptionButton, "b");
					
					// Construct filename
					// username_imageId.jpg
					
					Toast.makeText(context, "Downloading " + descriptionType, Toast.LENGTH_SHORT).show();
					Object mUser = getObjectField(mMedia, "p");
					String userName = (String) getObjectField(mUser, "a");
					String userFullName = (String) getObjectField(mUser, "b");
					String itemId = (String) getObjectField(mMedia, "t");
					String fileName = userName + "_" + itemId + "." + filenameExtension;
					
					File directory =
							new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/Instagram");
					if (!directory.exists())
						directory.mkdirs();
					
					DownloadManager.Request request = new DownloadManager.Request(Uri.parse(linkToDownload));
					request.setTitle(userFullName + "'s " + descriptionType);
					request.setDescription("Instagram " + descriptionType);
					request.allowScanningByMediaScanner();
					request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
					request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Instagram/" + fileName);

					DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
					manager.enqueue(request);
					
					param.setResult(null);
				}	
			}
		});
		
	}
}
