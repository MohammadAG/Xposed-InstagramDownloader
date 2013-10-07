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
		
		Class<?> MediaOptionsButton = findClass("com.instagram.android.widget.MediaOptionsButton", lpparam.classLoader);
		findAndHookMethod(MediaOptionsButton, "getMenuOptions", new XC_MethodHook() {
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
				setObjectField(param.thisObject, "mMenuOptions", newResult);
				mMenuOptions = (CharSequence[]) getObjectField(param.thisObject, "mMenuOptions");
				param.setResult(mMenuOptions);
			}
		});
		
		findAndHookMethod(MediaOptionsButton, "showMenu", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				mCurrentMediaOptionButton = param.thisObject;
			}
		});
		
		Class<?> MenuClickListener = findClass("com.instagram.android.widget.MediaOptionsButton$MenuClickListener", lpparam.classLoader);
		findAndHookMethod(MenuClickListener, "onClick", DialogInterface.class, int.class, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				CharSequence localCharSequence = mMenuOptions[(Integer) param.args[1]];
				if (mDownloadString.equals(localCharSequence)) {
					Object mMedia = getObjectField(mCurrentMediaOptionButton, "mMedia");
					Object mMediaType = getObjectField(mMedia, "mMediaType");
					Object videoType = getStaticObjectField(mMediaType.getClass(), "VIDEO");
					String linkToDownload;
					String filenameExtension;
					String descriptionType;
					if (mMediaType.equals(videoType)) {
						linkToDownload = (String) getObjectField(mMedia, "mHighResolutionVideoUrl");
						filenameExtension = "mp4";
						descriptionType = "video";
					} else {
						linkToDownload = (String) getObjectField(mMedia, "mStandardResolutionUrl");
						filenameExtension = "jpg";
						descriptionType = "photo";
					}
					
					Context context = (Context) getObjectField(mCurrentMediaOptionButton, "mActivityContext");
					
					// Construct filename
					// username_imageId.jpg
					
					Toast.makeText(context, "Downloading " + descriptionType, Toast.LENGTH_SHORT).show();
					Object mUser = getObjectField(mMedia, "mUser");
					String userName = (String) getObjectField(mUser, "mUsername");
					String userFullName = (String) getObjectField(mUser, "mFullName");
					String itemId = (String) getObjectField(mMedia, "mId");
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
