/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts;
import android.content.Context;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
/** Illustrated connection card displays only values supplied by the accessory controller. */
public final class AccessoryConnectionPreference extends Preference {
 private Integer battery;
 private CharSequence batteryLabel;
 private boolean initializing;
 public AccessoryConnectionPreference(Context context) {
  super(context);setLayoutResource(R.layout.accessory_connection_preference);setSelectable(false);setIconSpaceReserved(false);
 }
 public void setBattery(Integer value,CharSequence label,boolean connecting) {
  battery=value!=null&&value>=0&&value<=100?value:null;
  batteryLabel=label;initializing=connecting;notifyChanged();
 }
 @Override public void onBindViewHolder(PreferenceViewHolder holder) {
  super.onBindViewHolder(holder);
  ((TextView)holder.findViewById(R.id.accessory_battery_label)).setText(batteryLabel);
  ProgressBar progress=(ProgressBar)holder.findViewById(R.id.accessory_battery_bar);
  progress.setVisibility(battery==null?View.GONE:View.VISIBLE);if(battery!=null)progress.setProgress(battery);
  holder.findViewById(R.id.accessory_connect_spinner).setVisibility(initializing?View.VISIBLE:View.GONE);
 }
}
