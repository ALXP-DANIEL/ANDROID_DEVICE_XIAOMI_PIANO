#!/usr/bin/env python3
"""Run real V2 adapter against deterministic Android Bluetooth/scheduler boundaries."""
import pathlib, subprocess, tempfile
root=pathlib.Path(__file__).resolve().parents[1]
fixtures={
"android/os/SystemClock.java": "package android.os; public class SystemClock { public static long elapsedRealtime(){return 0;} }",
"android/os/Handler.java": """package android.os; import java.util.*; public class Handler {
public final Map<Runnable,Long> queue=new LinkedHashMap<>();
public boolean postDelayed(Runnable r,long delay){queue.put(r,delay);return true;}
public void removeCallbacks(Runnable r){queue.remove(r);}
public void runDelay(long delay){for(var e:new ArrayList<>(queue.entrySet()))if(e.getValue()==delay&&queue.remove(e.getKey())!=null)e.getKey().run();}
}""",
"android/bluetooth/BluetoothGattDescriptor.java": """package android.bluetooth; public class BluetoothGattDescriptor {
public static final byte[] ENABLE_NOTIFICATION_VALUE={1,0}; public boolean setValue(byte[] b){return true;}}
""",
"android/bluetooth/BluetoothGattCharacteristic.java": """package android.bluetooth; import java.util.*;
public class BluetoothGattCharacteristic { public static final int PROPERTY_WRITE=8,PROPERTY_NOTIFY=16,WRITE_TYPE_DEFAULT=2;
public byte[] value; public boolean descriptor=true; public int getProperties(){return 24;}
public BluetoothGattDescriptor getDescriptor(UUID id){return descriptor?new BluetoothGattDescriptor():null;}
public void setWriteType(int t){if(t!=2)throw new AssertionError();} public boolean setValue(byte[] b){value=b.clone();return true;}}
""",
"android/bluetooth/BluetoothGattService.java": """package android.bluetooth; import java.util.*;
public class BluetoothGattService { public final Map<UUID,BluetoothGattCharacteristic> chars=new HashMap<>();
public BluetoothGattCharacteristic getCharacteristic(UUID id){return chars.get(id);}}
""",
"android/bluetooth/BluetoothGatt.java": """package android.bluetooth; import java.util.*;
public class BluetoothGatt { public static final int GATT_SUCCESS=0,CONNECTION_PRIORITY_BALANCED=0;
public BluetoothGattService service=new BluetoothGattService(); public final List<byte[]> writes=new ArrayList<>();
public boolean reject; public int balanced; public BluetoothGattService getService(UUID id){return service;}
public boolean setCharacteristicNotification(BluetoothGattCharacteristic c,boolean b){return true;}
public boolean writeDescriptor(BluetoothGattDescriptor d){return true;}
public boolean writeCharacteristic(BluetoothGattCharacteristic c){if(reject)throw new IllegalArgumentException("Platform rejected value");writes.add(c.value.clone());return true;}
public boolean requestConnectionPriority(int p){balanced++;return true;}}
""",
}
with tempfile.TemporaryDirectory() as tmp:
    out=pathlib.Path(tmp); paths=[]
    for name,body in fixtures.items():
        path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body);paths.append(str(path))
    # Keep actual callback contract, excluding the unrelated V1 factory from this boundary fixture.
    base=root/'src/org/lineageos/pianoparts/stylus/firmware'
    interface=(base/'PenFirmwareGatt.java').read_text()
    start=interface.index('    static PenFirmwareGatt create(')
    end=interface.index('    void prepare(',start)
    path=out/'PenFirmwareGatt.java';path.write_text(interface[:start]+interface[end:]);paths.append(str(path))
    paths += [str(base/(name+'.java')) for name in ['FirmwareImage','MippV2Protocol','MippV2Session','MippV2Gatt']]
    paths.append(str(root/'tests/firmware/MippV2GattTest.java'))
    subprocess.run(['javac','-d',tmp,*paths],check=True)
    subprocess.run(['java','-cp',tmp,'MippV2GattTest'],check=True)
