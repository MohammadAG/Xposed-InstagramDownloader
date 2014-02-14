package com.mohammadag.xposedinstagramdownloader;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Environment;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class InstagramDownloader implements IXposedHookLoadPackage, IXposedHookZygoteInit {

	private CharSequence[] mMenuOptions = null;
	private CharSequence[] mDirectShareMenuOptions = null;
	private Object mCurrentMediaOptionButton;
	private Object mCurrentDirectShareMediaOptionButton;
	private static final String mDownloadString = "Download";
	private static final boolean PRINT_VIEW_CLASS_NAMES = false;
	
	private static final String MEDIA_OPTIONS_BUTTON_CLASS_NAME = "com.instagram.android.feed.a.a.x";
	private static final String DS_MEDIA_OPTIONS_BUTTON_CLASS_NAME = "com.instagram.android.directshare.g.ac";
	private static final String MEDIA_CLASS_NAME = "com.instagram.m.l";

	private static void log(String log) {
		XposedBridge.log("InstagramDownloader: " + log);
	}

	@Override
	public void initZygote(StartupParam startupParam) throws Throwable {
		if (!PRINT_VIEW_CLASS_NAMES)
			return;

		findAndHookMethod(View.class, "onTouchEvent", MotionEvent.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				XposedBridge.log(param.thisObject.getClass().getName());
			}
		});
	}

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals("com.instagram.android"))
			return;

		/* Hi Facebook team! Obfuscating the package isn't enough */
		final Class<?> MediaOptionsButton = findClass(MEDIA_OPTIONS_BUTTON_CLASS_NAME, lpparam.classLoader);
		final Class<?> DirectSharePermalinkMoreOptionsDialog = findClass(DS_MEDIA_OPTIONS_BUTTON_CLASS_NAME,
				lpparam.classLoader);

		XC_MethodHook injectDownloadIntoCharSequenceHook = new XC_MethodHook() {
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
				Field menuOptionsField;
				if (param.thisObject.getClass().getName().contains("directshare")) {
					menuOptionsField = XposedHelpers.findFirstFieldByExactType(param.thisObject.getClass(), CharSequence[].class);
				} else {
					menuOptionsField = XposedHelpers.findFirstFieldByExactType(MediaOptionsButton, CharSequence[].class);
				}
				menuOptionsField.set(param.thisObject, newResult);
				if (param.thisObject.getClass().getName().contains("directshare")) {
					mDirectShareMenuOptions  = (CharSequence[]) menuOptionsField.get(param.thisObject);
				} else {
					mMenuOptions = (CharSequence[]) menuOptionsField.get(param.thisObject);
				}
				param.setResult(newResult);
			}
		};

		findAndHookMethod(MediaOptionsButton, "b", injectDownloadIntoCharSequenceHook);
		findAndHookMethod(DirectSharePermalinkMoreOptionsDialog, "b", injectDownloadIntoCharSequenceHook);

		findAndHookMethod(MediaOptionsButton, "a", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				mCurrentMediaOptionButton = param.thisObject;
			}
		});

		findAndHookMethod(DirectSharePermalinkMoreOptionsDialog, "a", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				mCurrentDirectShareMediaOptionButton = param.thisObject;
			}
		});

		Class<?> DirectShareMenuClickListener = findClass("com.instagram.android.directshare.g.ai", lpparam.classLoader);
		findAndHookMethod(DirectShareMenuClickListener, "onClick", DialogInterface.class, int.class, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				CharSequence localCharSequence = mDirectShareMenuOptions[(Integer) param.args[1]];
				Context context = ((Dialog) param.args[0]).getContext();
				if (mDownloadString.equals(localCharSequence)) {
					Object mMedia = null;

					Field[] mCurrentMediaOptionButtonFields =
							mCurrentDirectShareMediaOptionButton.getClass().getDeclaredFields();
					for (Field iField : mCurrentMediaOptionButtonFields) {
						if (iField.getType().getName().equals(MEDIA_CLASS_NAME)) {
							mMedia = iField.get(mCurrentDirectShareMediaOptionButton);
							break;
						}
					}

					if (mMedia == null) {
						log("Unable to determine media");
						return;
					}

					if (isPackageInstalled(context, "com.mohammadag.xposedinstagramdownloaderdonate")) {
						downloadMedia(mCurrentDirectShareMediaOptionButton, mMedia);
					} else {
						showRequiresDonatePackage(context);
					}
					param.setResult(null);
				}
			}
		});
		
		Class<?> MenuClickListener = findClass("com.instagram.android.feed.a.a.z", lpparam.classLoader);
		findAndHookMethod(MenuClickListener, "onClick", DialogInterface.class, int.class, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				CharSequence localCharSequence = mMenuOptions[(Integer) param.args[1]];
				if (mDownloadString.equals(localCharSequence)) {
					Object mMedia = null;

					mMedia = getObjectField(mCurrentMediaOptionButton, "h");

					if (mMedia == null) {
						log("Unable to determine media");
						return;
					}

					downloadMedia(mCurrentMediaOptionButton, mMedia);
					param.setResult(null);
				}
			}
		});

	}

	@SuppressLint("NewApi")
	private static void downloadMedia(Object sourceButton, Object mMedia) {
		Field contextField =
				XposedHelpers.findFirstFieldByExactType(sourceButton.getClass(), Context.class);
		Context context = null;
		try {
			context = (Context) contextField.get(sourceButton);
		} catch (Exception e) {
			e.printStackTrace();
			log("Failed to get Context");
			return;
		}
		
		log("Downloading media...");
		Object mMediaType = getObjectField(mMedia, "b");

		Field[] mMediaFields = mMedia.getClass().getDeclaredFields();
		for (Field iField : mMediaFields) {
			String fieldType = iField.getClass().getName();
			if (fieldType.contains("com.instagram.model")
					&& !fieldType.contains("people")) {
				try {
					mMediaType = iField.get(mMedia);
				} catch (Exception e) {
					log("Failed to get MediaType class");
					Toast.makeText(context,
							"Failed to get MediaType. Downloading will not work with this version of Instagram, wait for an update",
							Toast.LENGTH_LONG).show();
					e.printStackTrace();
					return;
				}
			}
		}

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

		// Construct filename
		// username_imageId.jpg

		Toast.makeText(context, "Downloading " + descriptionType, Toast.LENGTH_SHORT).show();
		Object mUser = getObjectField(mMedia, "p");
		String userName = (String) getObjectField(mUser, "b");
		String userFullName = (String) getObjectField(mUser, "c");
		String itemId = (String) getObjectField(mMedia, "t");
		String fileName = userName + "_" + itemId + "." + filenameExtension;

		File directory =
				new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/Instagram");
		if (!directory.exists())
			directory.mkdirs();

		DownloadManager.Request request = new DownloadManager.Request(Uri.parse(linkToDownload));
		request.setTitle(userFullName + "'s " + descriptionType);
		request.setDescription("Instagram " + descriptionType);
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
			request.allowScanningByMediaScanner();
			request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
		}
		request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Instagram/" + fileName);

		DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
		manager.enqueue(request);
	}

	public static final boolean isPackageInstalled(Context context, String packageName) {
		PackageManager pm = context.getPackageManager();
		try {
			pm.getPackageInfo(packageName, PackageManager.GET_META_DATA);
			return "com.android.vending".equals(pm.getInstallerPackageName(packageName));
		} catch (NameNotFoundException e) {
			return false;
		}
	}

	@SuppressLint("NewApi")
	private static final void showRequiresDonatePackage(final Context context) {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
			AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_Holo_Light_Dialog);
			builder.setTitle("Requires donate package");
			builder.setMessage("This feature requires the Instagram Downloader donation package");
			builder.setPositiveButton("Go to Play Store", new OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					String url = "market://details?id=com.mohammadag.xposedinstagramdownloaderdonate";
					Intent intent = new Intent(Intent.ACTION_VIEW).setData(Uri.parse(url));
					intent.setPackage("com.android.vending");
					try {
						context.startActivity(intent);
					} catch (ActivityNotFoundException e) {
						Toast.makeText(context, "Play Store not found", Toast.LENGTH_SHORT).show();
					}
				}
			});
			builder.create().show();
		} else {
			String url = "market://details?id=com.mohammadag.xposedinstagramdownloaderdonate";
			Intent intent = new Intent(Intent.ACTION_VIEW).setData(Uri.parse(url));
			intent.setPackage("com.android.vending");
			Toast.makeText(context, "Requires donate package", Toast.LENGTH_SHORT).show();
			try {
				context.startActivity(intent);
			} catch (ActivityNotFoundException e) {
				Toast.makeText(context, "Play Store not found", Toast.LENGTH_SHORT).show();
			}
		}
	}
}
