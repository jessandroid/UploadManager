/*
 * Copyright (c) 2016.  All rights reserved. Lai Jiewen <alanljw12345@gmail,com
 */

package me.ctknight.uploadmanager;

import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import me.ctknight.uploadmanager.util.OpenHelper;

public class UploadReceiver extends BroadcastReceiver {
    private static Handler sAsyncHandler;

    static {
        final HandlerThread thread = new HandlerThread("UploadReceiver");
        thread.start();
        sAsyncHandler = new Handler(thread.getLooper());
    }

    private static String getString(Cursor cursor, String col) {
        return cursor.getString(cursor.getColumnIndexOrThrow(col));
    }

    private static int getInt(Cursor cursor, String col) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(col));
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {

        final String action = intent.getAction();
        if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
            final ConnectivityManager connManager = (ConnectivityManager) context.
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            final NetworkInfo info = connManager.getActiveNetworkInfo();
            if (info != null && info.isConnected()) {
                startService(context);
            }
        } else if (UploadContract.ACTION_RETRY.equals(action)) {
            startService(context);
        } else if (UploadContract.ACTION_LIST.equals(action)
                || UploadContract.ACTION_HIDE.equals(action)
                || UploadContract.ACTION_OPEN.equals(action)) {

            final PendingResult result = goAsync();
            if (result == null) {
                handleNotificationBroadcast(context, intent);

            } else {
                sAsyncHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handleNotificationBroadcast(context, intent);
                        result.finish();
                    }
                });
            }
        }

    }

    private void handleNotificationBroadcast(Context context, Intent intent) {
        final String action = intent.getAction();
        if (UploadContract.ACTION_LIST.equals(action)) {
            final long[] ids = intent.getLongArrayExtra(
                    UploadManager.EXTRA_NOTIFICATION_CLICK_UPLOAD_IDS);
            // TODO: 2016/2/18 add this in API doc.
            sendNotificationClickIntent(context, ids);
        } else if (UploadContract.ACTION_HIDE.equals(action)) {
            final long id = ContentUris.parseId(intent.getData());
            hideNotification(context, id);

        } else if (UploadContract.ACTION_OPEN.equals(action)) {
            final long id = ContentUris.parseId(intent.getData());
            openUploadFile(context, id);
            hideNotification(context, id);

        }
    }

    private void hideNotification(Context context, long id) {
        final int status;
        final int visibility;

        final Uri uri = ContentUris.withAppendedId(UploadContract.UPLOAD_URIS.CONTENT_URI, id);
        final Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        try {
            if (cursor.moveToFirst()) {
                status = getInt(cursor, UploadContract.UPLOAD_COLUMNS.COLUMN_STATUS);
                visibility = getInt(cursor, UploadContract.UPLOAD_COLUMNS.COLUMN_VISIBILITY);
            } else {
                Log.w("UploadReceiver", "Missing details for download " + id);
                return;
            }
        } finally {
            cursor.close();
        }
        if (UploadContract.isFailed(status) || UploadContract.isComplete(status) &&
                (visibility == UploadContract.VISIBILITY_STATUS.VISIBLE_COMPLETE
                        || visibility == UploadContract.VISIBILITY_STATUS.VISIBLE_ONLY_COMPLETION)) {
            final ContentValues values = new ContentValues();
            values.put(UploadContract.UPLOAD_COLUMNS.COLUMN_VISIBILITY,
                    UploadContract.VISIBILITY_STATUS.HIDDEN_COMPLETE);
            context.getContentResolver().update(uri, values, null, null);
        }
    }

    /**
     * Start activity to display the file represented by the given
     * {@link me.ctknight.uploadmanager.UploadContract.UPLOAD_COLUMNS#_ID}.
     */
    private void openUploadFile(Context context, long id) {
        if (!OpenHelper.startViewIntent(context, id, Intent.FLAG_ACTIVITY_NEW_TASK)) {
            Toast.makeText(context, R.string.upload_no_application_title, Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private void sendNotificationClickIntent(Context context, long[] ids) {
        final String packageName;
        final String clazz;

        final Uri uri = ContentUris.withAppendedId(
                UploadContract.UPLOAD_URIS.CONTENT_URI, ids[0]);
        final Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);

        try {
            if (cursor.moveToFirst()) {
                packageName = getString(cursor, UploadContract.UPLOAD_COLUMNS.COLUMN_NOTIFICATION_PACKAGE);
                clazz = getString(cursor, UploadContract.UPLOAD_COLUMNS.COLUMN_NOTIFICATION_CLASS);
            } else {
                Log.w("UploadReceiver", "Missing details for upload " + ids[0]);
                return;
            }
        } finally {
            cursor.close();
        }

        Intent appIntent = null;
        if (TextUtils.isEmpty(clazz)) {
            Log.w("UploadReceiver", "Missing class;skipping broadcast");
            return;
        } else if (TextUtils.isEmpty(packageName)) {
            Log.w("UploadReceiver", "Missing package name; skipping broadcast");
            return;
        }

        appIntent = new Intent(UploadManager.ACTION_NOTIFICATION_CLICKED);
        appIntent.setClassName(packageName, clazz);
        appIntent.putExtra(UploadManager.EXTRA_NOTIFICATION_CLICK_UPLOAD_IDS, ids);

        if (ids.length == 1) {
            appIntent.setData(uri);
        } else {
            appIntent.setData(UploadContract.UPLOAD_URIS.CONTENT_URI);
        }

        context.sendBroadcast(appIntent);
    }

    private void startService(Context context) {
        context.startService(new Intent(context, UploadService.class));
    }
}
