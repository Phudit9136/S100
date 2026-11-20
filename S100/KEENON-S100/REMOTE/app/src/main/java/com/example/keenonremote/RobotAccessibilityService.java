package com.example.keenonremote;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class RobotAccessibilityService extends AccessibilityService {
    static volatile RobotAccessibilityService instance;
    static String industryPkg;
    static volatile String appKeyword = "";                      // ว่าง = ค้นหาแอพ Industry (KEENON) อัตโนมัติ
    static final String[] AUTO = {"industry"};

    // ===== คำที่ใช้หาปุ่มในแอพ Industry (ถ้าชื่อปุ่มจริงไม่ตรง ให้แก้ตรงนี้) =====
    static final String[] ENTRY   = {"ตรงไป", "直达"};                                   // ปุ่มเข้าหน้าเลือกจุด (ถ้ามี)
    static final String[] START   = {"เริ่มทำงาน", "เริ่ม", "ออกเดินทาง", "Start", "Go", "开始", "出发"};
    static final String[] CANCEL  = {"ยกเลิก", "หยุด", "Cancel", "Stop", "取消", "停止"};
    static final String[] CONFIRM = {"ยืนยัน", "ตกลง", "ใช่", "OK", "Yes", "Confirm", "确定", "确认"};
    static final String[] MODES   = {"ตรงไป"};                                         // โหมดเดียวที่ใช้
    static final String[] END     = {"สิ้นสุดภารกิจ", "จบภารกิจ", "End Task", "End Mission", "结束任务"};   // ปุ่มแดงในหน้า "พัก"
    static final String[] RUNNING = {"กำลังทำงาน", "พัก", "Working", "Paused", "执行中", "暂停"};          // ชื่อหน้าตอนหุ่นกำลังวิ่ง/พัก
    static final String[] DIALOG_NO = {"ยกเลิก", "Cancel", "取消"};                    // ปุ่มปฏิเสธในหน้าต่างยืนยัน (ห้ามกด)
    static final String[] OTHER   = {"กลับ", "Back", "返回", "หน้าหลัก", "Home"};         // ไม่นับเป็น "จุด" ตอนดึงรายการ
    static final String NOT_FOUND = "Industry app not found (ดูชื่อแอพที่ /apps แล้วพิมพ์ในช่องชื่อแอพ)";

    final ReentrantLock lock = new ReentrantLock();             // ทำทีละคำสั่งเท่านั้น
    volatile boolean abort = false;
    volatile boolean cancelling = false;                        // กันกดยกเลิกซ้อนระหว่างที่กำลังยกเลิก                              // ตั้งเป็น true เพื่อหยุดคำสั่งที่กำลังรันอยู่

    @Override protected void onServiceConnected() { instance = this; }
    @Override public boolean onUnbind(Intent i) { instance = null; return super.onUnbind(i); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    static long now() { return System.currentTimeMillis(); }

    /** หาแอพ Industry จากชื่อใต้ไอคอนหรือชื่อ package */
    static Intent launchIntent(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> l = pm.queryIntentActivities(q, 0);
        String kw = appKeyword == null ? "" : appKeyword.trim().toLowerCase();
        for (ResolveInfo r : l) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(c.getPackageName())) continue;
            String label = String.valueOf(r.loadLabel(pm)).toLowerCase();
            String pk = pkg.toLowerCase();
            boolean hit = false;
            if (kw.length() > 0) {
                hit = label.contains(kw) || pk.contains(kw);
            } else {
                for (String k : AUTO) { if (label.contains(k) || pk.contains(k)) hit = true; }
            }
            if (hit) {
                industryPkg = pkg;
                Intent i = pm.getLaunchIntentForPackage(pkg);
                if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return i;
            }
        }
        return null;
    }

    boolean inApp(AccessibilityNodeInfo root) {
        if (root == null || industryPkg == null) return false;
        CharSequence pn = root.getPackageName();
        return pn != null && industryPkg.contentEquals(pn);
    }

    /** เปิดแอพให้อยู่หน้าจอ รอไม่เกิน ms คืน root ของหน้าต่าง หรือ null ถ้าไม่สำเร็จ/ถูกยกเลิก */
    AccessibilityNodeInfo ensureApp(long ms) {
        long end = now() + ms, lastLaunch = 0;
        while (now() < end && !abort) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (inApp(root)) return root;
            if (now() - lastLaunch > 4000) {
                Intent i = launchIntent(this);
                if (i == null) return null;
                startActivity(i);
                lastLaunch = now();
            }
            sleep(500);
        }
        return null;
    }

    /** หน้าจอที่หุ่นกำลังวิ่ง/พักอยู่ (ต้องยกเลิกก่อนถึงจะสั่งใหม่ได้) */
    boolean isBusy(AccessibilityNodeInfo root) {
        return root != null && (findAny(root, END) != null || findAny(root, RUNNING) != null);
    }

    // ================= เข้าโหมด (ตรงไป) =================
    /** พาแอพไปอยู่หน้าในของโหมดที่เลือก คืน root หรือ null */
    AccessibilityNodeInfo enterMode(String mode) {
        long end = now() + 25000, clickAt = 0;
        boolean clicked = false;
        int backs = 0;
        while (now() < end && !abort) {
            AccessibilityNodeInfo root = ensureApp(8000);
            if (root == null) return null;
            if (isBusy(root)) return null;                         // หุ่นกำลังทำงาน ห้ามกดย้อนกลับ
            boolean hasStart = findAny(root, START) != null;
            boolean hasMode = find(root, mode) != null;
            if (hasStart && hasMode) return root;                 // อยู่หน้าในของโหมดนี้แล้ว
            if (hasStart) {                                       // อยู่หน้าในของโหมดอื่น -> ถอยกลับหน้าแรก
                if (backs < 3) { performGlobalAction(GLOBAL_ACTION_BACK); backs++; clicked = false; sleep(900); continue; }
                return null;
            }
            if (!clicked) {
                AccessibilityNodeInfo m = find(root, mode);
                if (m != null) { click(m); clicked = true; clickAt = now(); sleep(1200); continue; }
                if (backs < 4) { performGlobalAction(GLOBAL_ACTION_BACK); backs++; sleep(900); continue; }
            } else if (now() - clickAt > 3500) {
                return getRootInActiveWindow();                   // กดแล้วแต่ไม่เห็นปุ่มเริ่ม สมมติว่าเข้าแล้ว
            }
            sleep(300);
        }
        return null;
    }

    // ================= สั่งไปจุด =================
    String go(Context ctx, String point, String floor, String mode) {
        if (point == null || point.length() == 0) return "no point";
        if (launchIntent(this) == null) return NOT_FOUND;
        if (!lock.tryLock()) return "busy: กำลังทำคำสั่งอื่นอยู่ (กดยกเลิกก่อน)";
        try {
            abort = false;
            if (mode == null || mode.length() == 0) mode = MODES[0];
            if (enterMode(mode) == null) {
                if (abort) return "aborted";
                if (isBusy(getRootInActiveWindow())) return "busy: หุ่นกำลังทำงานอยู่ กดยกเลิกก่อน";
                return "เข้าโหมด " + mode + " ไม่สำเร็จ: เห็นบนจอ = " + summary();
            }
            long end = now() + 30000, since = now();
            boolean pointClicked = false;
            while (now() < end) {
                if (abort) return "aborted";
                AccessibilityNodeInfo root = ensureApp(8000);
                if (root == null) return abort ? "aborted" : "เปิดแอพ Industry ไม่ขึ้น";
                if (!pointClicked) {
                    AccessibilityNodeInfo p = findPoint(root, point);
                    if (p != null) {
                        if (floor != null && floor.length() > 0) {
                            AccessibilityNodeInfo f = find(root, floor);
                            if (f != null) { click(f); sleep(500); p = findPoint(getRootInActiveWindow(), point); }
                        }
                        if (p != null && click(p)) { pointClicked = true; sleep(700); }
                        else sleep(300);
                        continue;
                    }
                    if (isBusy(root))
                        return "busy: หุ่นกำลังทำงานอยู่ กดยกเลิกก่อน";
                    if (now() - since > 8000)
                        return "ไม่เจอจุด " + point + " ในโหมด " + mode + ": เห็นบนจอ = " + summary();
                } else {
                    AccessibilityNodeInfo b = findAny(root, START);
                    if (b != null && b.isEnabled()) { click(b); return "sent to " + point; }
                }
                sleep(300);
            }
            return "timeout: เห็นบนจอ = " + summary();
        } finally {
            lock.unlock();
        }
    }

    // ================= ยกเลิก แล้วพร้อมสั่งต่อ =================
    /** ลำดับเดียวกับที่กดเอง: (หน้ากำลังทำงาน) กดปุ่มหยุด -> (หน้าพัก) สิ้นสุดภารกิจ -> (หน้าต่างยืนยัน) ตกลง */
    String cancel() {
        if (launchIntent(this) == null) return NOT_FOUND;
        if (cancelling) return "busy: กำลังยกเลิกอยู่";
        cancelling = true;
        abort = true;                       // ให้คำสั่ง go ที่ค้างอยู่หยุดก่อน
        lock.lock();
        try {
            abort = false;
            AccessibilityNodeInfo root = ensureApp(8000);
            if (root == null) return "เปิดแอพ Industry ไม่ขึ้น";
            boolean did = false, confirmed = false;
            int pauses = 0, ends = 0;
            long confirmAt = 0, end = now() + 25000;
            while (now() < end) {
                root = getRootInActiveWindow();
                if (!inApp(root)) { root = ensureApp(5000); if (root == null) break; }

                AccessibilityNodeInfo ok = findAny(root, CONFIRM);
                boolean dialog = ok != null && (did || findAny(root, DIALOG_NO) != null);
                boolean busy = dialog || isBusy(root);
                if (confirmed && !busy) break;                          // กลับหน้าปกติแล้ว
                if (confirmed && now() - confirmAt < 3500) { sleep(300); continue; }   // รอหน้าจอเปลี่ยนหลังกดตกลง

                if (dialog) {                                           // หน้าต่างถามยืนยัน -> กด "ตกลง" (ไม่กด "ยกเลิก")
                    click(ok); did = true; confirmed = true; confirmAt = now(); sleep(1200); continue;
                }
                AccessibilityNodeInfo e = findAny(root, END);
                if (e != null) {                                        // หน้า "พัก" -> สิ้นสุดภารกิจ
                    if (ends >= 3) break;
                    click(e); did = true; ends++; sleep(1200); continue;
                }
                if (findAny(root, RUNNING) != null) {                   // หน้า "กำลังทำงาน" -> กดปุ่มหยุดชั่วคราว (ไอคอน ไม่มีข้อความ)
                    if (pauses >= 3 || !clickPause(root))
                        return "กดปุ่มหยุดกลางจอไม่สำเร็จ (" + pauseWhy + ") ให้เปิด /dump ตอนหุ่นกำลังวิ่งแล้วส่งมาดู: เห็นบนจอ = " + summary();
                    did = true; pauses++; sleep(600); continue;
                }
                break;                                                  // ไม่มีอะไรให้ยกเลิก
            }
            if (!did) return "nothing to cancel (หุ่นไม่ได้ทำงานอยู่)";
            root = getRootInActiveWindow();
            if (isBusy(root)) return "ยกเลิกไม่สำเร็จ: เห็นบนจอ = " + summary();
            sleep(500);
            return "cancelled";
        } finally {
            cancelling = false;
            lock.unlock();
        }
    }

    String pauseWhy = "";

    /** กดปุ่มหยุดกลมกลางจอ แล้วรอจนเห็นหน้า "พัก" (มีปุ่มสิ้นสุดภารกิจ) คืน true เมื่อเข้าหน้านั้นจริง */
    boolean clickPause(AccessibilityNodeInfo root) {
        Rect w = new Rect();
        root.getBoundsInScreen(w);
        int px = w.centerX(), py = w.top + (int) (w.height() * 0.31);   // ตำแหน่งปุ่มหยุดกลมในหน้า "กำลังทำงาน"
        List<AccessibilityNodeInfo> targets = new ArrayList<AccessibilityNodeInfo>();

        // 1) ปุ่มกลมใหญ่ที่ไม่มีข้อความ
        AccessibilityNodeInfo[] best = new AccessibilityNodeInfo[1];
        long[] area = new long[1];
        scanPause(root, w, true, best, area);
        if (best[0] == null) scanPause(root, w, false, best, area);
        if (best[0] != null) addTarget(targets, best[0], w);

        // 2) ทุกโหนดที่ครอบตำแหน่งปุ่ม (เล็กสุดก่อน) แล้วไล่ขึ้นไปหาตัวที่กดได้
        List<AccessibilityNodeInfo> hits = new ArrayList<AccessibilityNodeInfo>();
        collectAt(root, px, py, hits);
        Collections.sort(hits, new Comparator<AccessibilityNodeInfo>() {
            public int compare(AccessibilityNodeInfo x, AccessibilityNodeInfo y) {
                Rect a = new Rect(), b = new Rect();
                x.getBoundsInScreen(a); y.getBoundsInScreen(b);
                long d = (long) a.width() * a.height() - (long) b.width() * b.height();
                return d < 0 ? -1 : (d > 0 ? 1 : 0);
            }
        });
        for (AccessibilityNodeInfo h : hits) addTarget(targets, h, w);

        int tried = 0;
        for (AccessibilityNodeInfo t : targets) {
            if (tried++ >= 6) break;
            if (t.performAction(AccessibilityNodeInfo.ACTION_CLICK) && waitPaused(1800)) return true;
            AccessibilityNodeInfo r2 = getRootInActiveWindow();
            if (r2 != null && findAny(r2, END) != null) return true;     // กดติดแต่หน้าจอเปลี่ยนช้า
        }
        tried = Math.min(tried, targets.size());

        // 3) วิธี Accessibility ไม่ได้ผล: แตะพิกัดกลางปุ่มด้วยสิทธิ์ root (ใช้ได้เมื่อแท็บเล็ตมี su)
        boolean su = suTap(px, py);
        if (su && waitPaused(1800)) return true;
        pauseWhy = "ลองกด " + tried + " จุดแล้วหน้าจอไม่เปลี่ยน" + (su ? "" : ", แท็บเล็ตไม่มี root");
        return false;
    }

    boolean waitPaused(long ms) {
        long end = now() + ms;
        while (now() < end) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && findAny(r, END) != null) return true;
            sleep(200);
        }
        return false;
    }

    /** เพิ่มโหนดที่กดได้ (ตัวมันเองหรือพ่อแม่ที่ใกล้สุด) เข้ารายการ ข้ามตัวที่ใหญ่เกือบเต็มจอ */
    void addTarget(List<AccessibilityNodeInfo> out, AccessibilityNodeInfo n, Rect screen) {
        while (n != null && !n.isClickable()) n = n.getParent();
        if (n == null || out.contains(n)) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if ((long) r.width() * r.height() > (long) screen.width() * screen.height() * 9 / 10) return;
        out.add(n);
    }

    void collectAt(AccessibilityNodeInfo n, int x, int y, List<AccessibilityNodeInfo> out) {
        if (n == null) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.contains(x, y)) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectAt(n.getChild(i), x, y, out);
    }

    boolean suTap(int x, int y) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "input tap " + x + " " + y});
            long end = now() + 3000;
            while (now() < end) {
                try { return p.exitValue() == 0; } catch (IllegalThreadStateException running) { sleep(100); }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    void scanPause(AccessibilityNodeInfo n, Rect screen, boolean needClickable, AccessibilityNodeInfo[] best, long[] area) {
        if (n == null) return;
        if (label(n).length() == 0 && (n.isClickable() || (!needClickable && String.valueOf(n.getClassName()).contains("Image")))) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            int bw = r.width(), bh = r.height();
            if (bw > 0 && bh > 0 && bw >= screen.width() * 0.12 && bw <= screen.width() * 0.7
                && bw * 10L >= bh * 7L && bw * 10L <= bh * 14L                       // ใกล้เคียงสี่เหลี่ยมจัตุรัส/วงกลม
                && r.centerY() < screen.top + screen.height() * 0.65) {              // อยู่ครึ่งบางของจอ
                long a = (long) bw * bh;
                if (a > area[0]) { area[0] = a; best[0] = n; }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) scanPause(n.getChild(i), screen, needClickable, best, area);
    }

    // ================= ดึงรายการจุดจากหน้าจอแอพ Industry =================
    String pointsJson(String mode) {
        if (launchIntent(this) == null) return json(false, NOT_FOUND, null);
        if (!lock.tryLock()) return json(false, "busy: กำลังทำคำสั่งอื่นอยู่", null);
        try {
            abort = false;
            AccessibilityNodeInfo root;
            if (mode != null && mode.length() > 0) {
                root = enterMode(mode);
                if (root == null) return json(false, "เข้าโหมด " + mode + " ไม่สำเร็จ: เห็นบนจอ = " + summary(), null);
                sleep(700);
                root = getRootInActiveWindow();
            } else {
                root = ensureApp(8000);
            }
            if (root == null) return json(false, "เปิดแอพ Industry ไม่ขึ้น", null);
            List<String> pts = new ArrayList<String>();
            collect(root, false, pts, true, 40);
            if (pts.isEmpty())
                return json(false, "ไม่เจอจุดในหน้านี้ (ดู /dump)", pts);
            return json(true, "พบ " + pts.size() + " รายการ", pts);
        } finally {
            lock.unlock();
        }
    }

    void collect(AccessibilityNodeInfo n, boolean clk, List<String> out, boolean pts, int max) {
        if (n == null || out.size() >= max) return;
        boolean c = clk || n.isClickable();
        String t = label(n);
        if (t.length() > 0 && t.length() <= (pts ? 24 : 60) && !out.contains(t) && (!pts || (c && !reserved(t)))) out.add(t);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), c, out, pts, max);
    }

    static boolean reserved(String t) {
        if (t.startsWith("ชั้น") || t.toLowerCase().startsWith("floor")) return true;   // ป้ายชั้น ไม่ใช่จุด
        String[][] all = {ENTRY, MODES, START, CANCEL, CONFIRM, OTHER, END, RUNNING};
        for (String[] a : all) for (String w : a) if (w.equalsIgnoreCase(t)) return true;
        return false;
    }

    String summary() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return "(ไม่มีหน้าต่าง)";
        List<String> out = new ArrayList<String>();
        collect(r, false, out, false, 15);
        StringBuilder sb = new StringBuilder();
        for (String s : out) { if (sb.length() > 0) sb.append(" | "); sb.append(s); }
        return sb.length() == 0 ? "(ไม่เจอข้อความเลย แอพอาจไม่เปิดให้อ่านหน้าจอ)" : sb.toString();
    }

    // ================= ตัวช่วย =================
    static String label(AccessibilityNodeInfo n) {
        CharSequence a = n.getText(), d = n.getContentDescription();
        String t = a != null ? a.toString().trim() : "";
        if (t.length() == 0 && d != null) t = d.toString().trim();
        return t;
    }

    AccessibilityNodeInfo findPoint(AccessibilityNodeInfo root, String t) {
        AccessibilityNodeInfo n = find(root, t);
        if (n == null && t.length() > 1) n = findContains(root, t.toLowerCase());
        return n;
    }

    AccessibilityNodeInfo findAny(AccessibilityNodeInfo root, String[] words) {
        for (String w : words) {
            AccessibilityNodeInfo n = find(root, w);
            if (n != null) return n;
        }
        return null;
    }

    AccessibilityNodeInfo find(AccessibilityNodeInfo n, String t) {
        if (n == null) return null;
        CharSequence a = n.getText(), d = n.getContentDescription();
        if ((a != null && a.toString().trim().equalsIgnoreCase(t)) || (d != null && d.toString().trim().equalsIgnoreCase(t))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = find(n.getChild(i), t);
            if (r != null) return r;
        }
        return null;
    }

    AccessibilityNodeInfo findContains(AccessibilityNodeInfo n, String t) {
        if (n == null) return null;
        String l = label(n).toLowerCase();
        if (l.length() > 0 && l.contains(t)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findContains(n.getChild(i), t);
            if (r != null) return r;
        }
        return null;
    }

    boolean click(AccessibilityNodeInfo n) {
        while (n != null && !n.isClickable()) n = n.getParent();
        return n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    static String json(boolean ok, String msg, List<String> pts) {
        StringBuilder b = new StringBuilder("{\"ok\":" + ok + ",\"msg\":\"" + esc(msg) + "\"");
        if (pts != null) {
            b.append(",\"points\":[");
            for (int i = 0; i < pts.size(); i++) { if (i > 0) b.append(","); b.append("\"").append(esc(pts.get(i))).append("\""); }
            b.append("]");
        }
        return b.append("}").toString();
    }

    String dump() {
        StringBuilder sb = new StringBuilder();
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return "no active window";
        sb.append("package: ").append(r.getPackageName()).append("\n");
        walk(r, 0, sb);
        return sb.toString();
    }
    static String boundsOf(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r.flattenToString();
    }
    void walk(AccessibilityNodeInfo n, int d, StringBuilder sb) {
        if (n == null) return;
        for (int i = 0; i < d; i++) sb.append("  ");
        sb.append(n.getClassName()).append(" | text=").append(n.getText()).append(" | desc=")
          .append(n.getContentDescription()).append(" | id=").append(n.getViewIdResourceName())
          .append(" | clickable=").append(n.isClickable()).append(" | enabled=").append(n.isEnabled())
          .append(" | bounds=").append(boundsOf(n)).append("\n");
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), d + 1, sb);
    }
}
