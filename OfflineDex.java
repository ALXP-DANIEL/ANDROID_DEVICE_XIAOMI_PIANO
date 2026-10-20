/* SPDX-License-Identifier: Apache-2.0 */
import java.io.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.rewriter.*;
import com.android.tools.smali.dexlib2.writer.pool.ReplayableDexPool;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;

public class OfflineDex {
  static final String SETTINGS = "Lcom/miui/notes/notesui/feature/note/presentation/settings/f;->A0(Ljava/lang/String;)V";
  static final String NAV = "Lcom/miui/notes/notesui/feature/note/presentation/list/AINoteListActivity;->onCreatePrimaryNavigation(Lmiuix/navigator/Navigator;Landroid/os/Bundle;)V";
  static final String LEGACY = "Ldm/a;->N0()V";
  static final String MENU="Lnj/k;->onCreateOptionsMenu(Landroid/view/Menu;)Z";
  static final String RESUME="Llj/i7;->onResume()V";
  static final String TOOLBAR="Lnj/d;-><init>(Landroid/view/View;Lnj/c;)V";
  static final Set<String> HOME = new HashSet<>(Arrays.asList(
      "Lsj/n1;->onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V",
      "Ltj/q;->onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V",
      "Lak/y2;->onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V"));
  static final Set<String> UI = new HashSet<>(Arrays.asList(SETTINGS, NAV, LEGACY, MENU, TOOLBAR, RESUME));
  static { UI.addAll(HOME); }
  static final Map<String,String> expectedUi = new HashMap<>();
  static final Map<String,Boolean> gates = new LinkedHashMap<>();
  static String key(com.android.tools.smali.dexlib2.iface.Method m) {
    StringBuilder s = new StringBuilder(m.getDefiningClass()+"->"+m.getName()+"(");
    for (CharSequence p:m.getParameterTypes()) s.append(p);
    return s.append(")").append(m.getReturnType()).toString();
  }
  static void collect(Class<?> c,Set<Class<?>> seen) {
    if(c==null || !seen.add(c))return;
    for(Class<?> i:c.getInterfaces())collect(i,seen);
    collect(c.getSuperclass(),seen);
  }
  static final Map<Class<?>,List<java.lang.reflect.Method>> getterCache=new HashMap<>();
  static List<java.lang.reflect.Method> getters(Class<?> type) {
    return getterCache.computeIfAbsent(type,key->{
      Set<Class<?>> seen=new HashSet<>();collect(key,seen);
      Map<String,java.lang.reflect.Method> methods=new TreeMap<>();
      for(Class<?> c:seen)if(c.isInterface()&&c.getName().startsWith("com.android.tools.smali.dexlib2.iface"))
        for(java.lang.reflect.Method m:c.getMethods())if(m.getParameterCount()==0&&m.getName().startsWith("get"))methods.put(m.getName(),m);
      return new ArrayList<>(methods.values());
    });
  }
  // DEX writers may widen const-string/goto and align payloads. Compare the
  // instruction graph, not byte offsets, without relaxing operand/reference checks.
  static final class Layout {
    final Map<Integer,Integer> ordinal=new HashMap<>();
    final Map<Integer,Integer> switchOwner=new HashMap<>();
    int at(int pc){Integer n=ordinal.get(pc);if(n==null)throw new IllegalStateException("Invalid code target "+pc);return n;}
    Layout(MethodImplementation impl) {
      int pc=0,n=0;
      for(Instruction i:impl.getInstructions()) {
        ordinal.put(pc,n);
        if(i.getOpcode()==Opcode.PACKED_SWITCH||i.getOpcode()==Opcode.SPARSE_SWITCH)
          switchOwner.put(pc+((OffsetInstruction)i).getCodeOffset(),pc);
        if(i.getOpcode()!=Opcode.NOP)n++;
        pc+=i.getCodeUnits();
      }
      ordinal.put(pc,n);
    }
  }
  static String graphValue(Object x,Layout layout,int pc) throws Exception {
    if(x==null)return "null";
    if(x instanceof Opcode) {
      Opcode o=(Opcode)x;
      if(o==Opcode.CONST_STRING_JUMBO)o=Opcode.CONST_STRING;
      if(o==Opcode.GOTO_16||o==Opcode.GOTO_32)o=Opcode.GOTO;
      return o.toString();
    }
    if(x instanceof CharSequence||x instanceof Number||x instanceof Boolean||x instanceof Enum)return x.toString();
    if(x instanceof Iterable) {
      StringBuilder result=new StringBuilder("[");for(Object i:(Iterable<?>)x)result.append(graphValue(i,layout,pc)).append(",");return result.append("]").toString();
    }
    SortedMap<String,String> result=new TreeMap<>();
    for(java.lang.reflect.Method m:getters(x.getClass())) {
        String name=m.getName();Object value=m.invoke(x);
        if(name.equals("getCodeUnits"))continue;
        if(name.equals("getCodeOffset"))value=layout.at(pc+((Number)value).intValue());
        if(name.equals("getCodeAddress")||name.equals("getStartCodeAddress")||name.equals("getHandlerCodeAddress"))value=layout.at(((Number)value).intValue());
        if(name.equals("getCodeUnitCount")&&x instanceof TryBlock) {
          TryBlock<?> block=(TryBlock<?>)x;value=layout.at(block.getStartCodeAddress()+block.getCodeUnitCount())-layout.at(block.getStartCodeAddress());
        }
        if(name.equals("getOffset")&&x instanceof com.android.tools.smali.dexlib2.iface.instruction.SwitchElement) {
          Integer owner=layout.switchOwner.get(pc);if(owner==null)throw new IllegalStateException("Orphan switch payload");
          value=layout.at(owner+((Number)value).intValue());
        }
        result.put(name,graphValue(value,layout,pc));
      }
    if(result.isEmpty())throw new IllegalStateException("Unhandled DEX graph value "+x.getClass());return result.toString();
  }
  static String implementationCanon(MethodImplementation impl) throws Exception {
    if(impl==null)return "null";Layout layout=new Layout(impl);StringBuilder result=new StringBuilder("registers="+impl.getRegisterCount());int pc=0;
    for(Instruction i:impl.getInstructions()) {
      if(i.getOpcode()!=Opcode.NOP)result.append(graphValue(i,layout,pc));pc+=i.getCodeUnits();
    }
    result.append(graphValue(impl.getTryBlocks(),layout,0));result.append(graphValue(impl.getDebugItems(),layout,0));return result.toString();
  }
  static Map<String,String> implementations(DexFile d) throws Exception {
    Map<String,String> result=new TreeMap<>();
    for(ClassDef c:d.getClasses())for(com.android.tools.smali.dexlib2.iface.Method m:c.getMethods())
      try {
        StringBuilder parameters=new StringBuilder();
        for(MethodParameter parameter:m.getParameters())
          parameters.append(parameter.getType()).append(":").append(parameter.getName()).append(";");
        result.put(key(m),parameters+"|"+implementationCanon(m.getImplementation()));
      }
      catch(Exception failure) {throw new IllegalStateException("Cannot verify "+key(m),failure);}
    return result;
  }
  public static void main(String[] args) throws Exception {
    Path input=Paths.get(args[0]), output=Paths.get(args[1]);
    for(int i=2;i<args.length;i++) {
      String[] p=args[i].split("=",2);
      if(gates.put(p[0],Boolean.parseBoolean(p[1]))!=null)throw new IllegalArgumentException("duplicate policy");
    }
    Map<String,Integer> counts=new TreeMap<>(), uiCounts=new TreeMap<>();
    Map<Path,DexFile> dexes=new LinkedHashMap<>();
    try(java.util.stream.Stream<Path> paths=Files.list(input)) {
      for(Path p:(Iterable<Path>)paths.sorted()::iterator) {
        DexFile d=DexFileFactory.loadDexFile(p.toFile(),Opcodes.getDefault()); dexes.put(p,d);
        for(ClassDef c:d.getClasses())for(com.android.tools.smali.dexlib2.iface.Method m:c.getMethods()) {
          if(UI.contains(key(m))) uiCounts.merge(key(m),1,Integer::sum);
          if(!gates.containsKey(key(m))) continue;
          if(!m.getReturnType().equals("Z") || !AccessFlags.STATIC.isSet(m.getAccessFlags()) || m.getImplementation()==null ||
             AccessFlags.NATIVE.isSet(m.getAccessFlags()) || AccessFlags.ABSTRACT.isSet(m.getAccessFlags()))
            throw new IllegalArgumentException("non-concrete boolean gate "+key(m));
          counts.merge(key(m),1,Integer::sum);
        }
      }
    }
    if(!counts.keySet().equals(gates.keySet()) || counts.values().stream().anyMatch(v->v!=1))
      throw new IllegalArgumentException("missing, changed or duplicate gate ABI: "+counts);
    if(!uiCounts.keySet().equals(UI) || uiCounts.values().stream().anyMatch(v->v!=1))
      throw new IllegalArgumentException("Pinned UI ABI changed: "+uiCounts);
    Files.createDirectories(output);
    for(Map.Entry<Path,DexFile> entry:dexes.entrySet()) {
      DexFile original=entry.getValue();
      DexFile rewritten=new DexRewriter(new RewriterModule(){
        @Override public Rewriter<com.android.tools.smali.dexlib2.iface.Method> getMethodRewriter(Rewriters r) {
          return new MethodRewriter(r){
            @Override public com.android.tools.smali.dexlib2.iface.Method rewrite(com.android.tools.smali.dexlib2.iface.Method m) {
              Boolean value=gates.get(key(m));
              if(value==null && !UI.contains(key(m)))return m;
              if(UI.contains(key(m))) {
                MutableMethodImplementation impl=new MutableMethodImplementation(m.getImplementation());
                int replacements=0, exits=0, filters=0;
                if(key(m).equals(RESUME)) {
                  int networkCalls=0;
                  for(int i=0;i<impl.getInstructions().size();i++) {
                    Instruction instruction=impl.getInstructions().get(i);
                    if(!(instruction instanceof ReferenceInstruction) ||
                        !(((ReferenceInstruction)instruction).getReference() instanceof MethodReference)) continue;
                    MethodReference ref=(MethodReference)((ReferenceInstruction)instruction).getReference();
                    if(ref.getDefiningClass().equals("Landroid/webkit/WebSettings;") &&
                        ref.getName().equals("setBlockNetworkLoads") &&
                        ref.getParameterTypes().equals(Arrays.asList("Z")) && ref.getReturnType().equals("V")) {
                      impl.replaceInstruction(i,new com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x(Opcode.NOP));
                      networkCalls++;
                    }
                  }
                  if(networkCalls!=1)throw new IllegalArgumentException("WebView network policy ABI changed");
                }
                if(key(m).equals(TOOLBAR)) {
                  for(int i=0;i<impl.getInstructions().size()-1;i++) {
                    Instruction instruction=impl.getInstructions().get(i);
                    if(instruction instanceof ReferenceInstruction && ((ReferenceInstruction)instruction).getReference() instanceof MethodReference) {
                      MethodReference ref=(MethodReference)((ReferenceInstruction)instruction).getReference();
                      if(ref.getDefiningClass().equals("Lgv/m;")&&ref.getName().equals("a")&&ref.getReturnType().equals("Ljava/util/List;")) {
                        Instruction move=impl.getInstructions().get(i+1);
                        if(move.getOpcode()!=Opcode.MOVE_RESULT_OBJECT)throw new IllegalArgumentException("Toolbar list result changed");
                        int register=((OneRegisterInstruction)move).getRegisterA();
                        impl.addInstruction(i+2,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,register,1,
                            new ImmutableMethodReference("Lorg/lineageos/notes/OfflineUi;","filterActions",Arrays.asList("Ljava/lang/Object;"),"Ljava/util/List;")));
                        impl.addInstruction(i+3,new com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT,register));
                        filters++;i+=3;
                      }
                    }
                  }
                  if(filters!=1)throw new IllegalArgumentException("Toolbar list ABI changed");
                }
                for(int i=0;i<impl.getInstructions().size();i++) {
                  Instruction instruction=impl.getInstructions().get(i);
                  if((key(m).equals(SETTINGS)||key(m).equals(NAV)) && instruction instanceof ReferenceInstruction &&
                      ((ReferenceInstruction)instruction).getReference() instanceof MethodReference) {
                    MethodReference ref=(MethodReference)((ReferenceInstruction)instruction).getReference();
                    if(ref.getDefiningClass().equals("Lfh/f;") && ref.getName().equals("a") &&
                        ref.getParameterTypes().isEmpty() && ref.getReturnType().equals("Z")) {
                      if(instruction.getOpcode()!=Opcode.INVOKE_VIRTUAL || !(instruction instanceof FiveRegisterInstruction) ||
                          ((FiveRegisterInstruction)instruction).getRegisterCount()!=1)
                        throw new IllegalArgumentException("Unexpected AI UI call shape");
                      FiveRegisterInstruction old=(FiveRegisterInstruction)instruction;
                      impl.replaceInstruction(i,new com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c(
                          Opcode.INVOKE_STATIC,1,old.getRegisterC(),0,0,0,0,
                          new ImmutableMethodReference("Lorg/lineageos/notes/OfflineUi;","hideAi",Arrays.asList("Ljava/lang/Object;"),"Z")));
                      replacements++;
                    }
                  }
                }
                if((key(m).equals(SETTINGS)||key(m).equals(NAV)) && replacements!=(key(m).equals(SETTINGS)?2:1))throw new IllegalArgumentException("AI UI gate inventory changed: "+key(m)+"="+replacements);
                if(key(m).equals(MENU)) {
                  // Parameter registers may be reused before return. Filter immediately
                  // after stock populates each menu, while its argument is still live.
                  for(int i=impl.getInstructions().size()-1;i>=0;i--) {
                    Instruction instruction=impl.getInstructions().get(i);
                    if(!(instruction instanceof ReferenceInstruction) ||
                        !(((ReferenceInstruction)instruction).getReference() instanceof MethodReference) ||
                        !(instruction instanceof FiveRegisterInstruction)) continue;
                    MethodReference ref=(MethodReference)((ReferenceInstruction)instruction).getReference();
                    if(!(ref.getName().equals("onCreateOptionsMenu") || ref.getName().equals("l")) ||
                        !ref.getParameterTypes().equals(Arrays.asList("Landroid/view/Menu;"))) continue;
                    FiveRegisterInstruction call=(FiveRegisterInstruction)instruction;
                    if(call.getRegisterCount()!=2)throw new IllegalArgumentException("Menu call ABI changed");
                    int after=i+1;
                    if(after<impl.getInstructions().size() &&
                        impl.getInstructions().get(after).getOpcode()==Opcode.MOVE_RESULT) after++;
                    impl.addInstruction(after,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                        call.getRegisterD(),1,new ImmutableMethodReference(
                            "Lorg/lineageos/notes/OfflineUi;","hideAiMenu",
                            Arrays.asList("Ljava/lang/Object;"),"V")));
                    exits++;
                  }
                  if(exits!=2)throw new IllegalArgumentException("Menu population ABI changed: "+exits);
                }
                if(key(m).equals(TOOLBAR)) {
                  int view=impl.getRegisterCount()-2;
                  impl.addInstruction(0,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,view,1,
                      new ImmutableMethodReference("Lorg/lineageos/notes/OfflineUi;","hideAiView",
                          Arrays.asList("Ljava/lang/Object;"),"V")));
                }
                if(!key(m).equals(NAV) && !key(m).equals(MENU) && !key(m).equals(TOOLBAR) && !key(m).equals(RESUME)) {
                  int incoming=1;
                  for(CharSequence type:m.getParameterTypes())incoming+=type.toString().equals("J")||type.toString().equals("D")?2:1;
                  int receiver=impl.getRegisterCount()-incoming;
                  String helper="hidePreferences";
                  if(HOME.contains(key(m))){receiver++;helper="hideHomeImport";}
                  for(int i=impl.getInstructions().size()-1;i>=0;i--)if(impl.getInstructions().get(i).getOpcode()==Opcode.RETURN_VOID) {
                    impl.addInstruction(i,new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,receiver,1,
                        new ImmutableMethodReference("Lorg/lineageos/notes/OfflineUi;",helper,Arrays.asList("Ljava/lang/Object;"),"V")));
                    exits++;
                  }
                  if(exits==0)throw new IllegalArgumentException("No settings exit");
                }
                try {
                  StringBuilder parameters=new StringBuilder();
                  for(MethodParameter parameter:m.getParameters())
                    parameters.append(parameter.getType()).append(":").append(parameter.getName()).append(";");
                  expectedUi.put(key(m),parameters+"|"+implementationCanon(impl));
                }catch(Exception error){throw new IllegalStateException(error);}
                return new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),
                    m.getAccessFlags(),m.getAnnotations(),m.getHiddenApiRestrictions(),impl);
              }
              int incoming=AccessFlags.STATIC.isSet(m.getAccessFlags())?0:1;
              for(CharSequence type:m.getParameterTypes()) incoming+=type.toString().equals("J")||type.toString().equals("D")?2:1;
              MethodImplementation impl=new ImmutableMethodImplementation(Math.max(1,incoming),
                Arrays.asList(new ImmutableInstruction11n(Opcode.CONST_4,0,value?1:0),
                              new ImmutableInstruction11x(Opcode.RETURN,0)),
                Collections.emptyList(),Collections.emptyList());
              return new ImmutableMethod(m.getDefiningClass(),m.getName(),m.getParameters(),m.getReturnType(),
                m.getAccessFlags(),m.getAnnotations(),m.getHiddenApiRestrictions(),impl);
            }
          };
        }
      }).getDexFileRewriter().rewrite(original);
      Path target=output.resolve(entry.getKey().getFileName());
      ReplayableDexPool.writeDex(target.toFile(),rewritten);
      DexFile reread=DexFileFactory.loadDexFile(target.toFile(),Opcodes.getDefault());
      Map<String,String> before=implementations(original), after=implementations(reread);
      if(!before.keySet().equals(after.keySet())) throw new IllegalStateException("method inventory changed");
      for(String k:before.keySet()) {
        if(gates.containsKey(k)) {
          com.android.tools.smali.dexlib2.iface.Method method=null;
          for(ClassDef c:reread.getClasses())for(com.android.tools.smali.dexlib2.iface.Method m:c.getMethods())if(key(m).equals(k))method=m;
          List<Instruction> instructions=new ArrayList<>();
          for(Instruction i:method.getImplementation().getInstructions())instructions.add(i);
          if(instructions.size()!=2 || instructions.get(0).getOpcode()!=Opcode.CONST_4 ||
             ((com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction)instructions.get(0)).getNarrowLiteral()!=(gates.get(k)?1:0) ||
             instructions.get(1).getOpcode()!=Opcode.RETURN || !method.getImplementation().getTryBlocks().isEmpty())
            throw new IllegalStateException("gate readback mismatch "+k);
        } else if(UI.contains(k)) {
          if(!after.get(k).equals(expectedUi.get(k)))throw new IllegalStateException("UI readback mismatch "+k);
        } else if(!before.get(k).equals(after.get(k))) {
          String a=before.get(k),b=after.get(k);int at=0;while(at<Math.min(a.length(),b.length())&&a.charAt(at)==b.charAt(at))at++;
          throw new IllegalStateException("non-target code changed "+k+" at "+at+" BEFORE "+a.substring(Math.max(0,at-70),Math.min(a.length(),at+200))+" AFTER "+b.substring(Math.max(0,at-70),Math.min(b.length(),at+200)));
        }
      }
    }
    System.out.println("offline DEX gates verified: "+counts.size());
  }
}
