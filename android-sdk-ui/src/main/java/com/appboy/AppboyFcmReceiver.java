package com.appboy;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.support.v4.app.NotificationManagerCompat;

import com.appboy.configuration.AppboyConfigurationProvider;
import com.appboy.push.AppboyNotificationActionUtils;
import com.appboy.push.AppboyNotificationUtils;
import com.appboy.support.AppboyLogger;
import com.appboy.ui.inappmessage.AppboyInAppMessageManager;

public final class AppboyFcmReceiver extends BroadcastReceiver {
  private static final String TAG = AppboyLogger.getAppboyLogTag(AppboyFcmReceiver.class);
  /**
   * @deprecated This intent was used in legacy integrations only.
   * Incoming intents should only be received via
   * {@link AppboyFcmReceiver#FIREBASE_MESSAGING_SERVICE_ROUTING_ACTION}
   */
  @Deprecated()
  private static final String FCM_RECEIVE_INTENT_ACTION = "com.google.android.c2dm.intent.RECEIVE";
  private static final String FCM_MESSAGE_TYPE_KEY = "message_type";
  private static final String FCM_DELETED_MESSAGES_KEY = "deleted_messages";
  private static final String FCM_NUMBER_OF_MESSAGES_DELETED_KEY = "total_deleted";

  protected static final String FIREBASE_MESSAGING_SERVICE_ROUTING_ACTION = "firebase_messaging_service_routing_action";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null) {
      AppboyLogger.w(TAG, "Received null intent. Doing nothing.");
      return;
    }
    Context applicationContext = context.getApplicationContext();
    PushHandlerRunnable pushHandlerRunnable = new PushHandlerRunnable(applicationContext, intent);
    new Thread(pushHandlerRunnable).start();
  }

  private static class PushHandlerRunnable implements Runnable {
    private final String mAction;
    private final Context mApplicationContext;
    private final Intent mIntent;

    PushHandlerRunnable(Context applicationContext, @NonNull Intent intent) {
      mApplicationContext = applicationContext;
      mIntent = intent;
      mAction = intent.getAction();
    }

    @Override
    public void run() {
      try {
        performWork();
      } catch (Exception e) {
        AppboyLogger.e(TAG, "Caught exception while performing the push "
            + "notification handling work. Action: " + mAction + " Intent: " + mIntent, e);
      }
    }

    private void performWork() {
      AppboyLogger.i(TAG, "Received broadcast message. Message: " + mIntent.toString());
      String action = mIntent.getAction();
      if (FCM_RECEIVE_INTENT_ACTION.equals(action)
          || FIREBASE_MESSAGING_SERVICE_ROUTING_ACTION.equals(action)
          || Constants.APPBOY_STORY_TRAVERSE_CLICKED_ACTION.equals(action)) {
        handlePushNotificationPayload(mApplicationContext, mIntent);
      } else if (Constants.APPBOY_CANCEL_NOTIFICATION_ACTION.equals(action)) {
        AppboyNotificationUtils.handleCancelNotificationAction(mApplicationContext, mIntent);
      } else if (Constants.APPBOY_ACTION_CLICKED_ACTION.equals(action)) {
        AppboyNotificationActionUtils.handleNotificationActionClicked(mApplicationContext, mIntent);
      } else if (Constants.APPBOY_STORY_CLICKED_ACTION.equals(action)) {
        AppboyNotificationUtils.handlePushStoryPageClicked(mApplicationContext, mIntent);
      } else if (Constants.APPBOY_PUSH_CLICKED_ACTION.equals(action)) {
        AppboyNotificationUtils.handleNotificationOpened(mApplicationContext, mIntent);
      } else if (Constants.APPBOY_PUSH_DELETED_ACTION.equals(action)) {
        AppboyNotificationUtils.handleNotificationDeleted(mApplicationContext, mIntent);
      } else {
        AppboyLogger.w(TAG, "The FCM receiver received a message not sent from Appboy. Ignoring the message.");
      }
    }
  }

  @VisibleForTesting
  static boolean handlePushNotificationPayload(Context context, Intent intent) {
    if (!AppboyNotificationUtils.isAppboyPushMessage(intent)) {
      return false;
    }

    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
    String messageType = intent.getStringExtra(FCM_MESSAGE_TYPE_KEY);
    if (FCM_DELETED_MESSAGES_KEY.equals(messageType)) {
      int totalDeleted = intent.getIntExtra(FCM_NUMBER_OF_MESSAGES_DELETED_KEY, -1);
      if (totalDeleted == -1) {
        AppboyLogger.w(TAG, "Unable to parse FCM message. Intent: " + intent.toString());
      } else {
        AppboyLogger.i(TAG, "FCM deleted " + totalDeleted + " messages. Fetch them from Appboy.");
      }
      return false;
    } else {
      Bundle fcmExtras = intent.getExtras();
      AppboyLogger.i(TAG, "Push message payload received: " + fcmExtras);

      // Parsing the Appboy data extras (data push).
      // We convert the JSON in the extras key into a Bundle.
      Bundle appboyExtras = AppboyNotificationUtils.getAppboyExtrasWithoutPreprocessing(fcmExtras);
      fcmExtras.putBundle(Constants.APPBOY_PUSH_EXTRAS_KEY, appboyExtras);

      if (!fcmExtras.containsKey(Constants.APPBOY_PUSH_RECEIVED_TIMESTAMP_MILLIS)) {
        fcmExtras.putLong(Constants.APPBOY_PUSH_RECEIVED_TIMESTAMP_MILLIS, System.currentTimeMillis());
      }

      // This call must occur after the "extras" parsing above since we're expecting
      // a bundle instead of a raw JSON string for the APPBOY_PUSH_EXTRAS_KEY key
      if (AppboyNotificationUtils.isUninstallTrackingPush(fcmExtras)) {
        // Note that this re-implementation of this method does not forward the notification to receivers.
        AppboyLogger.i(TAG, "Push message is uninstall tracking push. Doing nothing. Not forwarding this notification to broadcast receivers.");
        return false;
      }

      AppboyConfigurationProvider appConfigurationProvider = new AppboyConfigurationProvider(context);
      if (appConfigurationProvider.getIsInAppMessageTestPushEagerDisplayEnabled()
          && AppboyNotificationUtils.isInAppMessageTestPush(intent)
          && AppboyInAppMessageManager.getInstance().getActivity() != null) {
        // Pass this test in-app message along for eager display and bypass displaying a push
        AppboyLogger.d(TAG, "Bypassing push display due to test in-app message presence and "
            + "eager test in-app message display configuration setting.");
        AppboyInternal.handleInAppMessageTestPush(context, intent);
        return false;
      }

      // Parse the notification for any associated ContentCard
      AppboyNotificationUtils.handleContentCardsSerializedCardIfPresent(context, fcmExtras);

      if (AppboyNotificationUtils.isNotificationMessage(intent)) {
        AppboyLogger.d(TAG, "Received notification push");
        int notificationId = AppboyNotificationUtils.getNotificationId(fcmExtras);
        fcmExtras.putInt(Constants.APPBOY_PUSH_NOTIFICATION_ID, notificationId);
        IAppboyNotificationFactory appboyNotificationFactory = AppboyNotificationUtils.getActiveNotificationFactory();

        if (fcmExtras.containsKey(Constants.APPBOY_PUSH_STORY_KEY)) {
          if (!fcmExtras.containsKey(Constants.APPBOY_PUSH_STORY_IS_NEWLY_RECEIVED)) {
            AppboyLogger.d(TAG, "Received the initial push story notification.");
            fcmExtras.putBoolean(Constants.APPBOY_PUSH_STORY_IS_NEWLY_RECEIVED, true);
          }
        }

        Notification notification = appboyNotificationFactory.createNotification(appConfigurationProvider, context, fcmExtras, appboyExtras);

        if (notification == null) {
          AppboyLogger.d(TAG, "Notification created by notification factory was null. Not displaying notification.");
          return false;
        }

        notificationManager.notify(Constants.APPBOY_PUSH_NOTIFICATION_TAG, notificationId, notification);
        AppboyNotificationUtils.sendPushMessageReceivedBroadcast(context, fcmExtras);
        AppboyNotificationUtils.wakeScreenIfAppropriate(context, appConfigurationProvider, fcmExtras);

        // Set a custom duration for this notification.
        if (fcmExtras != null && fcmExtras.containsKey(Constants.APPBOY_PUSH_NOTIFICATION_DURATION_KEY)) {
          int durationInMillis = Integer.parseInt(fcmExtras.getString(Constants.APPBOY_PUSH_NOTIFICATION_DURATION_KEY));
          AppboyNotificationUtils.setNotificationDurationAlarm(context, AppboyFcmReceiver.class, notificationId, durationInMillis);
        }
        return true;
      } else {
        AppboyLogger.d(TAG, "Received data push");
        AppboyNotificationUtils.sendPushMessageReceivedBroadcast(context, fcmExtras);
        AppboyNotificationUtils.requestGeofenceRefreshIfAppropriate(context, fcmExtras);
        return false;
      }
    }
  }
}
