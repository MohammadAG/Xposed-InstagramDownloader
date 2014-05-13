package com.mohammadag.xposedinstagramdownloader;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;

import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
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
	private static String mDownloadTranslated;
	private static final boolean PRINT_VIEW_CLASS_NAMES = BuildConfig.DEBUG;

	private static Context mContext;

	// grep for MediaOptionsDialog.java
	private static final String FEED_CLASS_NAME = "com.instagram.android.feed.a.a.ab";
	// grep for Media.java
	private static final String MEDIA_CLASS_NAME = "com.instagram.n.l";
	// grep for MediaOptionsDialog.java
	private static final String MEDIA_OPTIONS_BUTTON_CLASS_NAME = "com.instagram.android.feed.a.a.y";

	// grep for one of items below
	private static final String DS_PACKAGE_NAME = "com.instagram.android.directshare.e";
	// grep for DirectSharePermalinkMoreOptionsDialog.java
	private static final String DS_MEDIA_OPTIONS_BUTTON_CLASS_NAME = DS_PACKAGE_NAME + ".ad";
	// grep for DirectSharePermalinkMoreOptionsDialog.java, should implement DialogInterface.OnClickListener
	private static final String DS_PERM_MORE_OPTIONS_DIALOG_CLASS_NAME = DS_PACKAGE_NAME + ".ai";

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
				android.util.Log.d("Xposed", param.thisObject.getClass().getName());
			}
		});
	}

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals("com.instagram.android"))
			return;

		XposedHelpers.findAndHookMethod("com.instagram.android.activity.ActivityInTab",
				lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				mContext = (Activity) param.thisObject;
			}
		});

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

				if (mContext == null) {
					try {
						Field f = XposedHelpers.findFirstFieldByExactType(param.thisObject.getClass(), Context.class);
						f.setAccessible(true);
						mContext = (Context) f.get(param.thisObject);
					} catch (Throwable t) {
						log("Unable to get Context, button not translated");
					}
				}

				if (mContext != null) {
					mDownloadTranslated = ResourceHelper.getString(mContext, R.string.the_not_so_big_but_big_button);
				}

				if (!array.contains(getDownloadString()))
					array.add(getDownloadString());
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

		Class<?> DirectShareMenuClickListener = findClass(DS_PERM_MORE_OPTIONS_DIALOG_CLASS_NAME, lpparam.classLoader);
		findAndHookMethod(DirectShareMenuClickListener, "onClick", DialogInterface.class, int.class, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				CharSequence localCharSequence = mDirectShareMenuOptions[(Integer) param.args[1]];
				if (mContext == null)
					mContext = ((Dialog) param.args[0]).getContext();
				if (getDownloadString().equals(localCharSequence)) {
					Object mMedia = null;

					Field[] mCurrentMediaOptionButtonFields =
							mCurrentDirectShareMediaOptionButton.getClass().getDeclaredFields();
					for (Field iField : mCurrentMediaOptionButtonFields) {
						if (iField.getType().getName().equals(MEDIA_CLASS_NAME)) {
							iField.setAccessible(true);
							mMedia = iField.get(mCurrentDirectShareMediaOptionButton);
							break;
						}
					}

					if (mMedia == null) {
						Toast.makeText(mContext, ResourceHelper.getString(mContext, R.string.direct_share_download_failed),
								Toast.LENGTH_SHORT).show();
						log("Unable to determine media");
						return;
					}

					if (isPackageInstalled(mContext, "com.mohammadag.xposedinstagramdownloaderdonate")) {
						downloadMedia(mCurrentDirectShareMediaOptionButton, mMedia);
					} else {
						showRequiresDonatePackage(mContext);
					}
					param.setResult(null);
				}
			}
		});

		Class<?> MenuClickListener = findClass(FEED_CLASS_NAME, lpparam.classLoader);
		findAndHookMethod(MenuClickListener, "onClick", DialogInterface.class, int.class, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				if (mContext == null)
					mContext = ((Dialog) param.args[0]).getContext();
				CharSequence localCharSequence = mMenuOptions[(Integer) param.args[1]];
				if (mDownloadString.equals(localCharSequence)) {
					Object mMedia = null;

					try {
						mMedia = getObjectField(mCurrentMediaOptionButton, "h");
					} catch (NoSuchFieldError e) {
						log("Failed to get media: " + e.getMessage());
						e.printStackTrace();
					}

					if (mMedia == null) {
						Toast.makeText(mContext, "Unable to determine media, download failed",
								Toast.LENGTH_SHORT).show();
						log("Unable to determine media");
						return;
					}

					try {
						downloadMedia(mCurrentMediaOptionButton, mMedia);
					} catch (Throwable t) {
						log("Unable to download media: " + t.getMessage());
						t.printStackTrace();
					}
					param.setResult(null);
				}
			}
		});

	}

	@SuppressLint("NewApi")
	private static void downloadMedia(Object sourceButton, Object mMedia) {
		Field contextField =
				XposedHelpers.findFirstFieldByExactType(sourceButton.getClass(), Context.class);
		if (mContext == null) {
			try {
				mContext = (Context) contextField.get(sourceButton);
			} catch (Exception e) {
				e.printStackTrace();
				log("Failed to get Context");
				return;
			}
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
					Toast.makeText(mContext,
							ResourceHelper.getString(mContext, R.string.mediatype_error),
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
		int descriptionTypeId = R.string.photo;
		if (mMediaType.equals(videoType)) {
			linkToDownload = (String) getObjectField(mMedia, "E");
			filenameExtension = "mp4";
			descriptionType = "video";
			descriptionTypeId = R.string.video;
		} else {
			linkToDownload = (String) getObjectField(mMedia, "s");
			filenameExtension = "jpg";
			descriptionType = "photo";
			descriptionTypeId = R.string.photo;
		}

		// Construct filename
		// username_imageId.jpg
		descriptionType = ResourceHelper.getString(mContext, descriptionTypeId);
		String toastMessage = ResourceHelper.getString(mContext, R.string.downloading, descriptionType);
		Toast.makeText(mContext, toastMessage, Toast.LENGTH_SHORT).show();
		Object mUser = getObjectField(mMedia, "p");
		String userName = (String) getObjectField(mUser, "b");
		String userFullName = (String) getObjectField(mUser, "c");
		String itemId = (String) getObjectField(mMedia, "t");
		String fileName = userName + "_" + itemId + "." + filenameExtension;

		if (TextUtils.isEmpty(userFullName)) {
			userFullName = userName;
		}

		File directory =
				new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/Instagram");
		if (!directory.exists())
			directory.mkdirs();

		String notificationTitle = ResourceHelper.getString(mContext, R.string.username_thing, userFullName, descriptionType);
		String description = ResourceHelper.getString(mContext, R.string.instagram_item,
				descriptionType);

		DownloadManager.Request request = new DownloadManager.Request(Uri.parse(linkToDownload));
		request.setTitle(notificationTitle);
		request.setDescription(description);
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
			request.allowScanningByMediaScanner();
			request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
		}
		request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Instagram/" + fileName);

		DownloadManager manager = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
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
		String title = ResourceHelper.getString(context, R.string.requires_donation_package_title);

		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
			String message = ResourceHelper.getString(context, R.string.requires_donation_package_message);
			String positiveButton = ResourceHelper.getString(context, R.string.go_to_play_store);
			AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_Holo_Light_Dialog);
			builder.setTitle(title);
			builder.setMessage(message);
			builder.setPositiveButton(positiveButton, new OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					String url = "market://details?id=com.mohammadag.xposedinstagramdownloaderdonate";
					Intent intent = new Intent(Intent.ACTION_VIEW).setData(Uri.parse(url));
					intent.setPackage("com.android.vending");
					try {
						context.startActivity(intent);
					} catch (ActivityNotFoundException e) {
						String playStoreNotFound = ResourceHelper.getString(context, R.string.play_store_not_found);
						Toast.makeText(context, playStoreNotFound, Toast.LENGTH_SHORT).show();
					}
				}
			});
			builder.create().show();
		} else {
			String url = "market://details?id=com.mohammadag.xposedinstagramdownloaderdonate";
			Intent intent = new Intent(Intent.ACTION_VIEW).setData(Uri.parse(url));
			intent.setPackage("com.android.vending");
			Toast.makeText(context, title, Toast.LENGTH_SHORT).show();
			try {
				context.startActivity(intent);
			} catch (ActivityNotFoundException e) {
				String playStoreNotFound = ResourceHelper.getString(context, R.string.play_store_not_found);
				Toast.makeText(context, playStoreNotFound, Toast.LENGTH_SHORT).show();
			}
		}
	}

	private String getDownloadString() {
		if (mDownloadTranslated == null)
			return mDownloadString;

		return mDownloadTranslated;
	}
}
