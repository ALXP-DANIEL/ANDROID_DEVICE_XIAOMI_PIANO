/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.lineageos.pianoparts.R
import java.util.Locale

class StylusFirmwareFragment : Fragment() {
 private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
 override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
  inflater.inflate(R.layout.stylus_firmware_page,container,false)
 override fun onViewCreated(view: View, state: Bundle?) {
  view.findViewById<Button>(R.id.pen_update_check).setOnClickListener { check() }
  view.findViewById<Button>(R.id.pen_update_start).setOnClickListener {
   val status=StylusStatusStore.snapshot(requireContext())
   if(status.updateState=="available") AlertDialog.Builder(requireContext())
    .setTitle(R.string.stylus_firmware_update)
    .setMessage(getString(R.string.stylus_firmware_confirm,status.updateVersion))
    .setNegativeButton(android.R.string.cancel,null)
    .setPositiveButton(R.string.stylus_firmware_update) { _,_ ->
     requireContext().sendBroadcast(Intent(StylusController.ACTION_UPDATE_FIRMWARE).setPackage(requireContext().packageName))
    }.show()
  }
 }
 override fun onResume() {
  super.onResume()
  StylusStatusStore.preferences(requireContext()).registerOnSharedPreferenceChangeListener(listener)
  refresh();check()
 }
 override fun onPause() {
  StylusStatusStore.preferences(requireContext()).unregisterOnSharedPreferenceChangeListener(listener)
  super.onPause()
 }
 private fun check() {
  requireContext().sendBroadcast(Intent(StylusController.ACTION_CHECK_FIRMWARE).setPackage(requireContext().packageName))
 }
 private fun refresh() {
  val page=view?:return
  val context=requireContext()
  val status=StylusStatusStore.snapshot(context)
  val connected=context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled==true &&
   status.state==StylusConnectionState.CONNECTED
  val busy=status.updateState in setOf("updating","verifying")
  page.findViewById<TextView>(R.id.pen_update_model).text=if(connected && status.vendorId!=null && status.productId!=null)
   String.format(Locale.ROOT,"%04X:%04X",status.vendorId,status.productId) else getString(R.string.stylus_unavailable)
  page.findViewById<TextView>(R.id.pen_update_revision).text=if(connected) status.firmwareRevision?:getString(R.string.stylus_unavailable)
   else getString(R.string.stylus_unavailable)
  page.findViewById<TextView>(R.id.pen_update_status).text=when(status.updateState) {
   "available"->getString(R.string.stylus_firmware_available,status.updateVersion)
   "updating"->getString(R.string.stylus_firmware_progress,status.updateProgress)
   "verifying"->getString(R.string.stylus_firmware_verifying)
   "complete"->getString(R.string.stylus_firmware_complete)
   "unsupported"->getString(R.string.stylus_firmware_unsupported)
   "invalid","failed"->getString(R.string.stylus_firmware_failed)
   "not_ready"->getString(R.string.stylus_firmware_not_ready)
   else->getString(R.string.stylus_firmware_unavailable)
  }
  page.findViewById<ProgressBar>(R.id.pen_update_progress).apply {
   visibility=if(busy) View.VISIBLE else View.GONE
   isIndeterminate=status.updateState=="verifying"
   progress=status.updateProgress.coerceIn(0,100)
  }
  page.findViewById<Button>(R.id.pen_update_check).isEnabled=connected&&!busy
  page.findViewById<Button>(R.id.pen_update_start).isEnabled=connected&&status.penInitialized&&
   status.updateState=="available"&&(status.battery?:-1)>=20&&status.penError==0
 }
}
