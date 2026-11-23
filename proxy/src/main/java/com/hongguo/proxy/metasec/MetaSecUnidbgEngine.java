/*
 * MetaSec (libmetasec_ml.so) signer under unidbg for com.phoenix.read.
 * Adapted from Lumorian/hongguo-gui signsvc/HgSign.java.
 * Native .so / license / cert stay on the private host under METASEC_HOME — not in git.
 */
package com.hongguo.proxy.metasec;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.Emulator;
import com.github.unidbg.Module;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.arm.backend.DynarmicFactory;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.*;
import com.github.unidbg.linux.android.dvm.array.ArrayObject;
import com.github.unidbg.linux.android.dvm.wrapper.DvmBoolean;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.linux.file.SimpleFileIO;
import com.github.unidbg.linux.file.DirectoryFileIO;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.spi.SyscallHandler;
import com.github.unidbg.virtualmodule.android.AndroidModule;
import com.github.unidbg.virtualmodule.android.JniGraphics;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

public class MetaSecUnidbgEngine extends AbstractJni implements IOResolver<AndroidFileIO> {

    private static final String PACKAGE_NAME = "com.phoenix.read";
    private static final String APK_INSTALL_PATH = "/data/app/com.phoenix.read-Qq1a2B3c4D5e6F7g8H9i0J==/base.apk";
    private static final int SDK_VERSION = 23;

    // 资产根目录（SO 库/证书/stub APK）：持久化在 ~/.cache/hongguo/hg，
    // override with METASEC_HOME; keep .so off git。不再放 /tmp（重启即丢，曾导致签名全败）。
    private static final String ASSETS = System.getenv().getOrDefault("METASEC_HOME",
            "/opt/hongguo-playback-proxy/metasec");
    private static final String SO_METASEC = ASSETS + "/lib/arm64-v8a/libmetasec_ml.so";
    private static final String SO_CSHARE = ASSETS + "/lib/arm64-v8a/libc++_shared.so";
    private static final String FAKE_APK = ASSETS + "/base.apk";
    private static final String MS_CERT = ASSETS + "/refso/data/ms_16777218.bin";
    private static final String VFILES = "/data/user/0/com/phoenix/read/files";
    private static final File MSDATA_HOST = new File(System.getenv().getOrDefault(
            "METASEC_DATA", ASSETS + "/msdata"));
    // mssdk 工作目录（原为独立硬编码路径且 resolve() 未使用 —— 修复为跟随 METASEC_DATA，
    // 使并发签名槽可各自持有独立状态）
    private static final String MSDATA_ROOT = MSDATA_HOST.getPath();

    private final AndroidEmulator emulator;
    private final VM vm;
    public final Module module;
    public final Memory memory;
    private final boolean loggable;
    private File soFile;

    public MetaSecUnidbgEngine(boolean loggable) throws Exception {
        this.loggable = loggable;
        soFile = new File(SO_METASEC);
        File cshare = new File(SO_CSHARE);
        File fakeApk = new File(FAKE_APK);

        File rootfs = createTempDir("metasec_rootfs");

        emulator = AndroidEmulatorBuilder
                .for64Bit()
                .setRootDir(rootfs)
                .setProcessName(PACKAGE_NAME)
                // 后端选择：默认 dynarmic（2026-10 实测：好 JVM 单签中位 40ms vs 235ms，约 5x；
                // 启动多 ~0.7s 被启动画面掩盖；好坏 JVM 抽签比例与后端无关）。METASEC_BACKEND=unicorn2 回退。
                .addBackendFactory("dynarmic".equals(System.getenv().getOrDefault("METASEC_BACKEND", "unicorn2"))
                        ? new DynarmicFactory(true) : new Unicorn2Factory(true))
                .build();

        emulator.setTimeout(15000);
        SyscallHandler<AndroidFileIO> handler = emulator.getSyscallHandler();
        handler.setVerbose(loggable);
        handler.setVerbose(false);
        handler.addIOResolver(this);

        memory = emulator.getMemory();
        memory.setLibraryResolver(new AndroidResolver(SDK_VERSION));

        Map<String, Integer> iNode = new java.util.LinkedHashMap<>();
        iNode.put("/data/system", 671745);
        iNode.put("/data/app", 327681);
        iNode.put("/sdcard/android", 294915);
        iNode.put("/data/user/0/com.phoenix.read", 655781);
        iNode.put("/data/user/0/com.phoenix.read/files", 655864);
        emulator.set("inode", iNode);
        emulator.set("uid", 10074);

        vm = emulator.createDalvikVM();
        vm.setJni(this);
        vm.setVerbose(loggable);

        new AndroidModule(emulator, vm).register(memory);
        new JniGraphics(emulator, vm).register(memory);

        vm.loadLibrary(cshare, false);
        DvmClass m = vm.resolveClass("ms/bd/c/m");
        DvmClass a4a = vm.resolveClass("ms/bd/c/a4$a", m);
        vm.resolveClass("com/bytedance/mobsec/metasec/ml/MS", a4a);
        DalvikModule dm = vm.loadLibrary(soFile, true);
        module = dm.getModule();
        System.out.println("[MetaSec] so base = 0x" + Long.toHexString(module.base));
        System.out.println("[MetaSec] calling JNI_OnLoad ...");
        long t0 = System.currentTimeMillis();
        dm.callJNI_OnLoad(emulator);
        System.out.println("[MetaSec] JNI_OnLoad done in " + (System.currentTimeMillis() - t0) + "ms");
    }

    private File createTempDir(String prefix) throws IOException {
        File tempDir = File.createTempFile(prefix, "");
        tempDir.delete();
        if (!tempDir.mkdirs()) throw new IOException("mkdir fail " + tempDir);
        tempDir.deleteOnExit();
        return tempDir;
    }

    public String signRaw(long offset, String url, String header) {
        try {
            Number number = module.callFunction(emulator, offset, url, header);
            if (number == null) return null;
            UnidbgPointer result = memory.pointer(number.longValue());
            if (result == null) return null;
            return result.getString(0);
        } catch (Throwable t) {
            System.out.println("[MetaSec] signRaw error: " + t);
            return null;
        }
    }

    /** call k3.a(cmd, a1, handle, str, obj) via registered JNI */
    public DvmObject<?> k3(int cmd, int a1, long handle, String str, DvmObject<?> obj) {
        DvmClass k3 = vm.resolveClass("ms/bd/c/m");
        if (loggable) System.out.println("[K3] call cmd=0x" + Integer.toHexString(cmd) + " a1=" + a1 + " handle=" + handle + " str=" + (str==null?"null":str.substring(0,Math.min(40,str.length()))) + " obj=" + obj);
        DvmObject<?> _r = k3.callStaticJniMethodObject(emulator,
                "a(IIJLjava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;",
                cmd, a1, handle, str == null ? null : new StringObject(vm, str), obj);
        if (loggable) System.out.println("[K3] ret " + _r);
        return _r;
    }

    public static void main2(String[] args) throws Exception { }


    @Override
    public DvmObject<?> callStaticObjectMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        if (loggable) System.out.println("[MetaSec] callStaticObjectMethodV: " + signature);
        switch (signature) {
            case "com/bytedance/mobsec/metasec/ml/MS->b(IIJLjava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;": {
                int i = vaList.getIntArg(0);
                Object sArg = null, oArg = null;
                try { sArg = vaList.getObjectArg(3); } catch (Throwable ig) {}
                try { oArg = vaList.getObjectArg(4); } catch (Throwable ig) {}
                return handleMSMethod(vm, i,
                        sArg instanceof StringObject ? ((StringObject) sArg).getValue() : null,
                        oArg == null ? null : (oArg instanceof DvmObject ? ((DvmObject<?>) oArg).getValue() : String.valueOf(oArg)));
            }
            case "java/lang/Thread->currentThread()Ljava/lang/Thread;":
                return vm.resolveClass("java/lang/Thread").newObject(Thread.currentThread());
            case "java/lang/Long->valueOf(J)Ljava/lang/Long;": {
                long v = vaList.getLongArg(0);
                return vm.resolveClass("java/lang/Long").newObject(v);
            }
            case "java/lang/Integer->valueOf(I)Ljava/lang/Integer;": {
                int v = vaList.getIntArg(0);
                return vm.resolveClass("java/lang/Integer").newObject(v);
            }
            case "java/lang/Boolean->valueOf(Z)Ljava/lang/Boolean;": {
                return DvmBoolean.valueOf(vm, vaList.getIntArg(0) != 0);
            }
            case "java/lang/String->valueOf(Z)Ljava/lang/String;": {
                return new StringObject(vm, String.valueOf(vaList.getIntArg(0) != 0));
            }
        }
        return super.callStaticObjectMethodV(vm, dvmClass, signature, vaList);
    }

    private static final java.io.File PREFS_FILE = new File(System.getenv().getOrDefault(
            "METASEC_PREFS", ASSETS + "/msprefs.json"));
    private static final java.util.Map<String, String> PREFS = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean prefsLoaded = false;

    private static void prefsLoad() {
        if (prefsLoaded) return;
        prefsLoaded = true;
        try {
            if (PREFS_FILE.isFile()) {
                com.alibaba.fastjson.JSONObject o = com.alibaba.fastjson.JSON.parseObject(new String(java.nio.file.Files.readAllBytes(PREFS_FILE.toPath()), "UTF-8"));
                for (java.util.Map.Entry<String, Object> e : o.entrySet()) PREFS.put(e.getKey(), String.valueOf(e.getValue()));
            }
        } catch (Throwable ig) {}
    }

    private static void prefsSave() {
        try {
            PREFS_FILE.getParentFile().mkdirs();
            com.alibaba.fastjson.JSONObject o = new com.alibaba.fastjson.JSONObject();
            for (java.util.Map.Entry<String, String> e : PREFS.entrySet()) o.put(e.getKey(), e.getValue());
            java.nio.file.Files.write(PREFS_FILE.toPath(), o.toJSONString().getBytes("UTF-8"));
        } catch (Throwable ig) {}
    }

    /** mssdk prefs separator (native-decrypted once) */
    public volatile String prefsSep = "\u0000";

    private DvmObject<?> handleMSMethod(BaseVM vm, int methodId, String str, Object obj) {
        if (loggable) System.out.println("[MetaSec] MS method id: " + methodId + " str=" + str + " obj=" + obj);
        switch (methodId) {
            case 16777250: { // SharedPreferences read: (prefsName, key)
                prefsLoad();
                String v = PREFS.get(str + prefsSep + obj);
                if (loggable) System.out.println("[MetaSec] prefs.read " + str + " + " + obj + " => " + (v == null ? "null" : v.substring(0, Math.min(80, v.length()))));
                return v == null ? null : new StringObject(vm, v);
            }
            case 16777251: { // SharedPreferences write: ("name<sep>key", value)
                prefsLoad();
                String key = str;
                // keep full packed string as the store key (read side uses name + sep + key)
                PREFS.put(key, String.valueOf(obj));
                prefsSave();
                if (loggable) System.out.println("[MetaSec] prefs.write " + key + " = " + String.valueOf(obj).substring(0, Math.min(80, String.valueOf(obj).length())));
                return null;
            }
            case 65539:
                return new StringObject(vm, "/data/user/0/com/phoenix/read/files/.msdata");
            case 33554433:
            case 33554434:
                return DvmBoolean.valueOf(vm, true);
            case 16777232:
                return vm.resolveClass("java.lang.Integer").newObject(Integer.parseInt(System.getenv().getOrDefault("METASEC_VERSION_CODE", "74832")));
            case 16777233:
                return new StringObject(vm, System.getenv().getOrDefault("METASEC_VERSION_NAME", "7.4.8.32"));
            case 16777218: {
                try {
                    File f = new File(MS_CERT);
                    if (f.exists()) {
                        byte[] fileData = Files.readAllBytes(f.toPath());
                        return new ByteArray(vm, fileData);
                    }
                    System.out.println("[MetaSec] cert file missing: " + MS_CERT);
                    return null;
                } catch (IOException e) {
                    return null;
                }
            }
            case 268435470:
                return vm.resolveClass("java/lang/Long").newObject(System.currentTimeMillis());
            default:
                return null;
        }
    }

    @Override
    public DvmObject<?> callObjectMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        switch (signature) {
            case "android/content/Context->getPackageName()Ljava/lang/String;":
                return new StringObject(vm, PACKAGE_NAME);
            case "android/content/Context->getApplicationContext()Landroid/content/Context;":
                return dvmObject;
            case "android/content/Context->getFilesDir()Ljava/io/File;":
                return vm.resolveClass("java/io/File").newObject("/data/user/0/com.phoenix.read/files");
            case "android/content/Context->getCacheDir()Ljava/io/File;":
                return vm.resolveClass("java/io/File").newObject("/data/user/0/com.phoenix.read/cache");
            case "java/io/File->getAbsolutePath()Ljava/lang/String;":
                return new StringObject(vm, String.valueOf(dvmObject.getValue()));
            case "java/lang/Integer->toString()Ljava/lang/String;":
                return new StringObject(vm, String.valueOf(dvmObject.getValue()));
            case "java/lang/Long->toString()Ljava/lang/String;":
                return new StringObject(vm, String.valueOf(dvmObject.getValue()));
            case "java/lang/Thread->getStackTrace()[Ljava/lang/StackTraceElement;": {
                StackTraceElement[] elements = Thread.currentThread().getStackTrace();
                DvmObject[] objs = new DvmObject[elements.length];
                for (int i = 0; i < elements.length; i++) {
                    objs[i] = vm.resolveClass("java/lang/StackTraceElement").newObject(elements[i]);
                }
                return new ArrayObject(objs);
            }
            case "java/lang/StackTraceElement->getClassName()Ljava/lang/String;": {
                StackTraceElement element = (StackTraceElement) dvmObject.getValue();
                return new StringObject(vm, element.getClassName());
            }
            case "java/lang/StackTraceElement->getMethodName()Ljava/lang/String;": {
                StackTraceElement element = (StackTraceElement) dvmObject.getValue();
                return new StringObject(vm, element.getMethodName());
            }
            case "java/lang/Thread->getBytes(Ljava/lang/String;)[B": {
                String arg0 = (String) vaList.getObjectArg(0).getValue();
                return new ByteArray(vm, arg0.getBytes(StandardCharsets.UTF_8));
            }
        }
        return super.callObjectMethodV(vm, dvmObject, signature, vaList);
    }

    @Override
    public int getStaticIntField(BaseVM vm, DvmClass dvmClass, String signature) {
        if ("com/bytedance/mobsec/metasec/ml/MS->a()V".equals(signature)) {
            return 0x40;
        }
        return super.getStaticIntField(vm, dvmClass, signature);
    }

    @Override
    public void callVoidMethod(BaseVM vm, DvmObject<?> dvmObject, String signature, VarArg varArg) {
        if ("com/bytedance/mobsec/metasec/ml/MS->a()V".equals(signature)) {
            return;
        }
        super.callVoidMethod(vm, dvmObject, signature, varArg);
    }

    @Override
    public int callIntMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        if ("java/lang/Integer->intValue()I".equals(signature)) {
            Object value = dvmObject.getValue();
            if (value instanceof Integer) return (Integer) value;
            if (value instanceof String) return Integer.parseInt((String) value);
        }
        return super.callIntMethodV(vm, dvmObject, signature, vaList);
    }

    @Override
    public boolean callBooleanMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        if ("java/lang/Boolean->booleanValue()Z".equals(signature)) {
            Object value = dvmObject.getValue();
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof String) return Boolean.parseBoolean((String) value);
        }
        return super.callBooleanMethodV(vm, dvmObject, signature, vaList);
    }

    @Override
    public long callLongMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        if ("java/lang/Long->longValue()J".equals(signature)) {
            Object value = dvmObject.getValue();
            if (value instanceof Long) return (Long) value;
        }
        return super.callLongMethodV(vm, dvmObject, signature, vaList);
    }

    @Override
    public FileResult resolve(Emulator emulator, String pathname, int oflags) {

        if (loggable) System.out.println("[MetaSec] io: " + pathname + " oflags=0o" + Integer.toOctalString(oflags));
        if (pathname.equals("/data/user/0/com/phoenix/read/files") || pathname.startsWith(VFILES)) {
            try {
                String rel = pathname.equals("/data/user/0/com/phoenix/read/files") ? "" : pathname.substring("/data/user/0/com/phoenix/read/files/".length());
                java.io.File host = new java.io.File(MSDATA_ROOT, rel);
                if (rel.isEmpty()) {
                    return FileResult.success(new DirectoryFileIO(oflags, pathname, host));
                }
                if (rel.equals("files") || rel.equals("files/.msdata") || rel.equals("files/.msdata/mssdk") || rel.equals("files/.msdata/mssdk/ml")) {
                    host.mkdirs();
                    return FileResult.success(new DirectoryFileIO(oflags, pathname, host));
                }
                String base = host.getName();
                if (base.startsWith(".msp_") || base.startsWith(".mss_") || base.startsWith(".msf3")) {
                    host.getParentFile().mkdirs();
                    if (!host.exists() && !host.createNewFile()) return FileResult.failed(1);
                    return FileResult.success(new SimpleFileIO(oflags, host, pathname));
                }
            } catch (Exception e) {
                System.out.println("[MetaSec] msdata resolve err: " + e);
            }
        }
        if (pathname.contains("libmetasec_ml.so")) {
            return FileResult.success(new SimpleFileIO(oflags, soFile, pathname));
        }
        if (pathname.contains("base.apk")) {
            return FileResult.success(new SimpleFileIO(oflags, new File(FAKE_APK), pathname));
        }
        return null;
    }

    private static String loadLicense() {
        String env = System.getenv("METASEC_LICENSE");
        if (env != null && !env.trim().isEmpty()) return env.trim();
        try {
            java.io.File f = new java.io.File(ASSETS, "license.b64");
            if (f.isFile()) {
                String t = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!t.isEmpty()) return t;
            }
        } catch (Exception ig) {}
        return null;
    }

    public void initFull(String deviceId, String installId, String channel) {
        DvmClass ctxClass = vm.resolveClass("android/content/Context");
        DvmObject<?> ctx = ctxClass.newObject(null);
        System.out.println("[MetaSec] step1: set context");
        try { k3(16777219, 0, 0L, null, ctx); } catch (Throwable t) { System.out.println("step1 err: " + t); }
        String license = loadLicense();
        if (license == null || license.isEmpty()) {
            throw new IllegalStateException("MetaSec license missing: set METASEC_LICENSE or place " + ASSETS + "/license.b64");
        }
        String settingCfg = "fidhWm1xcXVha3ZaZnF3aSc/cXdwYCknYVp2bGJrczBaZnF3aSc/cXdwYCknYVprZHNsWmZxd2knP2NkaXZgeA==";
        com.alibaba.fastjson.JSONArray cfg = new com.alibaba.fastjson.JSONArray();
        cfg.add("8662");          // appID
        cfg.add("");              // subAppID
        cfg.add("");              // sdkID
        cfg.add(license);         // license
        cfg.add("");              // s4 constant
        cfg.add(channel);         // channel
        cfg.add(deviceId);        // deviceID
        cfg.add("");              // bdDeviceID
        cfg.add(installId);       // installID
        cfg.add("");              // sessionID
        cfg.add("0");          // clientType
        cfg.add("-1");         // ovRegionType
        cfg.add("99999");      // collectMode
        cfg.add(deviceId);        // fetchedDid
        cfg.add("1");          // origin
        cfg.add(new com.alibaba.fastjson.JSONArray());  // customInfo
        com.alibaba.fastjson.JSONArray adv = new com.alibaba.fastjson.JSONArray();
        adv.add("kSt"); adv.add(settingCfg);
        cfg.add(adv);             // advanceInfo
        System.out.println("[MetaSec] step2: init config");
        try {
            DvmObject<?> r = k3(67108865, 0, 0L, cfg.toString(), null);
            System.out.println("[MetaSec] init result: " + r + " value=" + (r == null ? null : r.getValue()));
        } catch (Throwable t) { System.out.println("step2 err: " + t); }
    }

    private volatile String lastUrl = null;
    private volatile java.util.Map<String,String> cached = new java.util.LinkedHashMap<>();

    private static final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ExecutorService> emuExecRef = new java.util.concurrent.atomic.AtomicReference<>();

    public synchronized java.util.Map<String,String> doSign(String url, boolean trace) throws Exception {
        return doSign(url, trace, new byte[0], 12);
    }

    public synchronized java.util.Map<String,String> doSign(String url, boolean trace, byte[] bodyBytes) throws Exception {
        return doSign(url, trace, bodyBytes, 12);
    }

    public synchronized java.util.Map<String,String> doSign(String url, boolean trace, byte[] bodyBytes, int rounds) throws Exception {
        if (rounds <= 0) rounds = 12;
        for (int round = 0; round < rounds; round++) {
            System.err.println("[MetaSec] round " + round + " start");
            // kick the collector: fresh handle each round + schedule background threads
            long h = handleVal;
            try {
                DvmObject<?> nh = k3(67108866, 0, 0L, null, null);
                if (nh != null && nh.getValue() instanceof Long) { h = (Long) nh.getValue(); }
            } catch (Throwable ig) { }
            try { emulator.getThreadDispatcher().runThreads(500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Throwable ig) { }
            tracing = trace;
            try {
                DvmObject<?> res = k3(33554438, 0, h, url, new ByteArray(vm, bodyBytes));
                if (res != null) {
                    Object v = res.getValue();
                    Object[] arr = (v instanceof Object[]) ? (Object[]) v : null;
                    if (arr != null) {
                        java.util.Map<String,String> out = new java.util.LinkedHashMap<>();
                        for (int i = 0; i + 1 < arr.length; i += 2) {
                            Object k = arr[i], val = arr[i+1];
                            out.put(String.valueOf(k instanceof DvmObject ? ((DvmObject<?>)k).getValue() : k),
                                    String.valueOf(val instanceof DvmObject ? ((DvmObject<?>)val).getValue() : val));
                        }
                        if (out.containsKey("X-Khronos")) return out;
                    }
                }
            } catch (Throwable ig) { }
            Thread.sleep(100);
        }
        return null;
    }


    // ---- BL-target discovery (HG_TRACE=1) ----
    private static final java.util.Map<Long, long[]> BLT = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    private boolean tracing = false;

    private void installTraceHook() {
        emulator.getBackend().hook_add_new(new com.github.unidbg.arm.backend.CodeHook() {
            @Override public void onAttach(com.github.unidbg.arm.backend.UnHook uh) {}
            @Override public void detach() {}
            @Override public void hook(com.github.unidbg.arm.backend.Backend bk, long address, int size, Object user) {
                if (!tracing) return;
                try {
                    byte[] insn = bk.mem_read(address, 4);
                    int w = ((insn[0] & 0xff) << 24) | ((insn[1] & 0xff) << 16) | ((insn[2] & 0xff) << 8) | (insn[3] & 0xff);
                    if ((w & 0xFC000000) == 0x94000000) {
                        long imm = w & 0x03FFFFFF;
                        if ((imm & 0x02000000) != 0) imm |= 0xFFFFFFFFFC000000L;
                        long target = address + (imm << 2);
                        long[] cnt = BLT.get(target);
                        if (cnt == null) BLT.put(target, new long[]{1});
                        else cnt[0]++;
                    }
                } catch (Throwable t) {}
            }
        }, module.base, module.base + module.size, null);
    }

    private void dumpTrace() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Long, long[]> e : BLT.entrySet()) {
            sb.append("0x").append(Long.toHexString(e.getKey() - module.base)).append(" ").append(e.getValue()[0]).append('\n');
        }
        try { java.nio.file.Files.write(java.nio.file.Paths.get("/tmp/bl_targets.txt"), sb.toString().getBytes()); } catch (Exception ig) {}
        System.out.println("[MetaSec] BL targets: " + BLT.size() + " -> /tmp/bl_targets.txt");
    }

    private long handleVal = 0;

    /** Recursively delete the mssdk state directory: state files bind to (url, body); wiping before each request
     *  ≈ fresh collector state — this is the key to enabling consecutive signatures of different URLs
     *  within a resident process (with stale state, the collector cannot complete after a URL change) */
    public static void wipeMsdata() {
        try { deleteRecursively(MSDATA_HOST); } catch (Throwable ig) { }
    }

    private static void deleteRecursively(File f) {
        File[] ls = f.listFiles();
        if (ls != null) for (File c : ls) deleteRecursively(c);
        // 清理失败绝不能静默：残留状态文件绑定 (url, body)，会直接毁掉后续签名，必须可见
        if (!f.delete() && f.exists())
            System.err.println("[MetaSec] wipe failed: " + f);
    }

    /** SIGN-FAIL + 连续失败计数（≥2 → SERVE-RESTART + exit(3)，Node 侧自动重建）。
     *  畸形输入与签名失败同语义：连发坏行说明两端协议失步，重启最干净。 */
    private static int registerFail(int consecutiveFails) {
        System.out.println("[MetaSec] SIGN-FAIL");
        System.out.flush();
        if (++consecutiveFails >= 2) {
            System.err.println("[MetaSec] 2 consecutive fails -> restart");
            System.out.println("[MetaSec] SERVE-RESTART");
            System.out.flush();
            System.exit(3);
        }
        return consecutiveFails;
    }

    public static void main(String[] args) throws Exception {
        String did = System.getenv().getOrDefault("METASEC_DID", "3788282713867595");
        String iid = System.getenv().getOrDefault("METASEC_IID", "3788282713871691");

        if (args.length > 0 && args[0].equals("--serve")) {
            // Resident signer: stdin is one JSON line {"url":…, "body_b64":…} (body_b64 optional, gzip request body);
            // each line outputs one [MetaSec] SIGN-JSON {…} or [MetaSec] SIGN-FAIL. 12 rounds of retry per URL; 2 consecutive
            // failures exit with exit(3), the caller (api.js) automatically rebuilds the process. No longer exit
            // after a successful signature (常驻).
            MetaSecUnidbgEngine S = new MetaSecUnidbgEngine(Boolean.getBoolean("metasec.verbose"));
            S.initFull(did, iid, "google");
            System.out.println("[MetaSec] SERVE-READY");
            System.out.flush();
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8));
            int consecutiveFails = 0;
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String url = line;
                byte[] bodyBytes = new byte[0];
                int rounds = 12;
                if (line.startsWith("{")) {
                    try {
                        com.alibaba.fastjson.JSONObject req = com.alibaba.fastjson.JSON.parseObject(line);
                        url = req.getString("url");
                        String b64 = req.getString("body_b64");
                        if (b64 != null && !b64.isEmpty())
                            bodyBytes = java.util.Base64.getDecoder().decode(b64);
                        Integer r = req.getInteger("rounds");
                        if (r != null && r > 0 && r <= 12) rounds = r;
                    } catch (Throwable t) {
                        consecutiveFails = registerFail(consecutiveFails);
                        continue;
                    }
                }
                if (url == null || url.isEmpty()) {
                    consecutiveFails = registerFail(consecutiveFails);
                    continue;
                }
                // msdata state binds to (url, body). Timing matters: must NOT wipe before the first
                // request (the state just built by initFull would be destroyed, same as killing the collector);
                // wipe after each request completes, so the next request gets a fresh state.
                // METASEC_WIPE=none|after|before can override (for debugging comparison).
                String wipeMode = System.getenv().getOrDefault("METASEC_WIPE", "after");
                if ("before".equals(wipeMode)) wipeMsdata();
                java.util.Map<String,String> out = null;
                try { out = S.doSign(url, false, bodyBytes, rounds); } catch (Throwable ig) { }
                if ("after".equals(wipeMode)) wipeMsdata();
                if (out != null) {
                    System.out.println("[MetaSec] SIGN-JSON " + com.alibaba.fastjson.JSON.toJSONString(out));
                    consecutiveFails = 0;
                } else {
                    consecutiveFails = registerFail(consecutiveFails);
                }
                System.out.flush();
            }
            return;
        }

        // batch mode: sign one URL, write METASEC_OUT, exit
        boolean vv = args.length > 1 && args[0].equals("-v");
        String url = vv ? args[1] : args[0];
        MetaSecUnidbgEngine sign = new MetaSecUnidbgEngine(vv);
        sign.initFull(did, iid, "google");
        DvmObject<?> h = sign.k3(67108866, 0, 0L, null, null);
        sign.handleVal = (h == null) ? 0 : (Long) h.getValue();
        byte[] bodyBytes = new byte[0];
        String bodyPath = System.getenv("METASEC_BODY");
        if (bodyPath != null && !bodyPath.isEmpty()) {
            bodyBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(bodyPath));
            System.out.println("[MetaSec] body bytes: " + bodyBytes.length);
        }
        java.util.Map<String,String> out = sign.doSign(url, false, bodyBytes);
        if (out == null) { System.out.println("[MetaSec] SIGN-FAIL"); System.out.flush(); Runtime.getRuntime().halt(3); }
        String json = com.alibaba.fastjson.JSON.toJSONString(out);
        String outPath = System.getenv("METASEC_OUT");
        if (outPath == null || outPath.isEmpty()) outPath = "/tmp/hgsign/sign_out.json";
        java.nio.file.Path op = java.nio.file.Paths.get(outPath);
        if (op.getParent() != null) java.nio.file.Files.createDirectories(op.getParent());
        java.nio.file.Files.write(op, json.getBytes("UTF-8"));
        System.out.println("[MetaSec] SIGN-JSON " + json);
        System.out.println("[MetaSec] SIGN-OK");
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }
}
