package org.lineageos.pianoparts.stylus;
public final class PenMenuGeometryTest {
 public static void main(String[] args) {
  for (int n=2;n<=6;n++) {
   PenMenuGeometry g=new PenMenuGeometry(n);
   if(g.sweep!=-20*(n-1)) throw new AssertionError("arc sweep");
   for(int i=0;i<n;i++) {
    if(g.x[i]<31.2f||g.x[i]>g.width-31.2f||g.y[i]<31.2f||g.y[i]>g.height-31.2f)
     throw new AssertionError("pressed icon clipped");
    if(i>0) {
     double distance=Math.hypot(g.x[i]-g.x[i-1],g.y[i]-g.y[i-1]);
     if(Math.abs(distance-2*196*Math.sin(Math.toRadians(10)))>.001) throw new AssertionError("spacing");
    }
   }
  }
  if(PenMenuGeometry.clamp(-10,0,100)!=0||PenMenuGeometry.clamp(200,0,100)!=100||PenMenuGeometry.clamp(5,10,-20)!=10)
   throw new AssertionError("screen clamp");
  try {new PenMenuGeometry(1);throw new AssertionError("invalid count");}catch(IllegalArgumentException expected){}
  System.out.println("Pen menu geometry checks passed");
 }
}
