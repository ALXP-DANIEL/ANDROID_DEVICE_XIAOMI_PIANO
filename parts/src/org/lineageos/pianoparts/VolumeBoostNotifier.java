/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.widget.Toast;

/**
 * Piano's speaker volume curve puts the HyperOS boost level on the top media
 * volume step. Like stock, tell the user when they reach it.
 */
class VolumeBoostNotifier extends BroadcastReceiver {

    private static final String EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String EXTRA_PREV_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";

    private final AudioManager mAudioManager;

    VolumeBoostNotifier(Context context) {
        mAudioManager = context.getSystemService(AudioManager.class);
        context.registerReceiver(this, new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION),
                Context.RECEIVER_EXPORTED);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) {
            return;
        }
        int max = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int value = intent.getIntExtra(EXTRA_VALUE, -1);
        int prev = intent.getIntExtra(EXTRA_PREV_VALUE, -1);
        // Only when stepping onto the top step, and only on the speaker.
        if (value == max && prev < max && isSpeakerActive()) {
            Toast.makeText(context, R.string.volume_boost_on, Toast.LENGTH_LONG).show();
        }
    }

    private boolean isSpeakerActive() {
        AudioAttributes media = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build();
        for (AudioDeviceAttributes device : mAudioManager.getDevicesForAttributes(media)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                return true;
            }
        }
        return false;
    }
}
