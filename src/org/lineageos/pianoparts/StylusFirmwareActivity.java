/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts;
import android.os.Bundle;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import org.lineageos.pianoparts.stylus.StylusFirmwareFragment;
public final class StylusFirmwareActivity extends CollapsingToolbarBaseActivity {
 @Override protected void onCreate(Bundle state) {
  super.onCreate(state);
  if(state==null)getSupportFragmentManager().beginTransaction()
   .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,new StylusFirmwareFragment()).commit();
 }
}
