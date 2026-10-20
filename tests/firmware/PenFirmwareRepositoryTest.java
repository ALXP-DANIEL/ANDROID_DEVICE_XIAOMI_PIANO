package org.lineageos.pianoparts.stylus.firmware;
import java.nio.file.*;
import java.security.MessageDigest;
public final class PenFirmwareRepositoryTest {
 public static void main(String[] args) throws Exception {
  Path root=Files.createTempDirectory("piano-firmware");
  try {
   if(PenFirmwareRepository.find(root,34,20609,"1.0.0")!=null)throw new AssertionError("absent manifest");
   byte[] image={1,2,3}; Files.write(root.resolve("pen.img"),image);
   StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(image))sha.append(String.format("%02x",b));
   String manifest="{\"file\":\"pen.img\",\"vendorId\":34,\"productId\":20609,\"profile\":\"p81c-suota-v1\",\"targetVersion\":\"1.0.1\",\"sha256\":\""+sha+"\"}";
   Files.writeString(root.resolve("manifest.json"),manifest);
   if(PenFirmwareRepository.find(root,34,20609,"1.0.0")==null)throw new AssertionError("new revision");
   if(PenFirmwareRepository.find(root,34,12929,"1.0.0")!=null)throw new AssertionError("wrong model");
   if(PenFirmwareRepository.find(root,34,20609,"1.0.1")!=null||PenFirmwareRepository.find(root,34,20609,"2.0.0")!=null)throw new AssertionError("downgrade");
   Files.writeString(root.resolve("manifest.json"),manifest.replace("pen.img","../pen.img"));
   try{PenFirmwareRepository.find(root,34,20609,"1.0.0");throw new AssertionError("traversal");}catch(IllegalArgumentException expected){}
   Files.writeString(root.resolve("manifest.json"),manifest);Files.write(root.resolve("pen.img"),new byte[]{4});
   try{PenFirmwareRepository.find(root,34,20609,"1.0.0");throw new AssertionError("digest");}catch(IllegalArgumentException expected){}
   Files.write(root.resolve("pen.bin"),image);
   String v2=manifest.replace("pen.img","pen.bin").replace("20609","20867").replace("p81c-suota-v1","mipp-suota-v2");
   Files.writeString(root.resolve("manifest.json"),v2);
   if(PenFirmwareRepository.find(root,34,20867,"1.0.0")==null)throw new AssertionError("V2 model-bound image");
   if(PenFirmwareRepository.find(root,34,20867,"1.0.1")!=null)throw new AssertionError("V2 same revision");
   try{PenFirmwareRepository.find(root,34,20868,"1.0.0");throw new AssertionError("V2 wrong model");}catch(IllegalArgumentException expected){}
   Files.writeString(root.resolve("manifest.json"),v2.replace("mipp-suota-v2","p81c-suota-v1"));
   try{PenFirmwareRepository.find(root,34,20867,"1.0.0");throw new AssertionError("V2 wrong profile");}catch(IllegalArgumentException expected){}
   System.out.println("Trusted pen firmware repository checks passed");
  }finally{try(var files=Files.list(root)){for(Path p:files.toList())Files.delete(p);}Files.delete(root);}
 }
}
