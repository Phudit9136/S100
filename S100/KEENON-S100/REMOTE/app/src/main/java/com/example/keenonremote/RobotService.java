package com.example.keenonremote;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.IBinder;
import java.io.*;
import java.net.*;

public class RobotService extends Service {
    static final int PORT = 5555;
    volatile boolean running = false;
    ServerSocket ss;

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent i, int f, int id) {
        if (!running) {
            running = true;
            new Thread(new Runnable() { public void run() { loop(); }}).start();
        }
        return START_STICKY;
    }
    void loop() {
        try {
            ss = new ServerSocket(PORT);
            while (running) {
                final Socket s = ss.accept();
                new Thread(new Runnable() { public void run() { handle(s); }}).start();
            }
        } catch (Exception e) { running = false; }
    }
    void handle(Socket s) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = r.readLine();
            String h; while ((h = r.readLine()) != null && h.length() > 0) { }
            if (line == null) { s.close(); return; }
            String path = line.split(" ")[1];
            Uri u = Uri.parse("http://x" + path);
            String type = "application/json", body;
            RobotAccessibilityService acc = RobotAccessibilityService.instance;
            String p = u.getPath() == null ? "/" : u.getPath();
            String app = u.getQueryParameter("app");
            if (app != null) RobotAccessibilityService.appKeyword = app;
            if (p.equals("/go")) {
                String point = u.getQueryParameter("point");
                String floor = u.getQueryParameter("floor");
                String mode = u.getQueryParameter("mode");
                if (acc == null) body = json(false, "Accessibility not enabled");
                else { String m = acc.go(this, point, floor, mode); body = json(m.startsWith("sent"), m); }
            } else if (p.equals("/cancel")) {
                if (acc == null) body = json(false, "Accessibility not enabled");
                else { String m = acc.cancel(); body = json(m.startsWith("cancelled") || m.startsWith("nothing"), m); }
            } else if (p.equals("/points")) {
                body = acc == null ? json(false, "Accessibility not enabled") : acc.pointsJson(u.getQueryParameter("mode"));
            } else if (p.equals("/apps")) {
                type = "text/plain";
                body = apps();
            } else if (p.equals("/dump")) {
                type = "text/plain";
                body = acc == null ? "Accessibility not enabled" : acc.dump();
            } else {
                type = "text/html";
                body = PAGE;
            }
            byte[] b = body.getBytes("UTF-8");
            OutputStream o = s.getOutputStream();
            o.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + b.length +
                "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes());
            o.write(b); o.flush(); s.close();
        } catch (Exception e) { try { s.close(); } catch (Exception ignored) { } }
    }

    static String json(boolean ok, String msg) {
        return "{\"ok\":" + ok + ",\"msg\":\"" + RobotAccessibilityService.esc(msg) + "\"}";
    }

    String apps() {
        PackageManager pm = getPackageManager();
        StringBuilder sb = new StringBuilder("== แอพที่เปิดได้ (ชื่อใต้ไอคอน | package) ==\n");
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(q, 0))
            sb.append(r.loadLabel(pm)).append(" | ").append(r.activityInfo.packageName).append("\n");
        sb.append("\n== แอพหน้าหลัก (Home/Launcher) ==\n");
        Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo r : pm.queryIntentActivities(h, 0))
            sb.append(r.loadLabel(pm)).append(" | ").append(r.activityInfo.packageName).append("\n");
        return sb.toString();
    }

    static final String PAGE =
        "<!doctype html><html lang=th><meta charset=utf-8>\n"
        + "<meta name=viewport content='width=device-width,initial-scale=1'>\n"
        + "<meta name=color-scheme content=dark>\n"
        + "<title>Keenon Remote</title>\n"
        + "<style>\n"
        + ":root{--bg:#090b0f;--panel:#11151c;--line:#1e2531;--txt:#e7eaf0;--mut:#7f8a9d;--acc:#7cc4ff;--ok:#4ade80;--err:#f87171;--busy:#fbbf24}\n"
        + "*{box-sizing:border-box}\n"
        + "html{background:var(--bg)}\n"
        + "body{margin:0;min-height:100vh;color:var(--txt);font-family:'Noto Sans Thai','Sarabun','Segoe UI',Tahoma,sans-serif;line-height:1.5;display:flex;justify-content:center;padding:40px 18px;background:radial-gradient(900px 420px at 50% -80px,#13203a 0,transparent 70%),var(--bg)}\n"
        + ".wrap{width:100%;max-width:640px}\n"
        + "h1{font-size:24px;font-weight:600;letter-spacing:.2px;margin:0 0 22px}\n"
        + ".panel{background:var(--panel);border:1px solid var(--line);border-radius:18px;padding:22px}\n"
        + ".panel+.panel{margin-top:14px}\n"
        + "label{display:block;font-size:14px;color:var(--mut);margin-bottom:8px}\n"
        + ".l2{margin-top:14px}\n"
        + "input{width:100%;background:#0b0e14;border:1px solid var(--line);border-radius:12px;color:var(--txt);font:inherit;font-size:17px;padding:12px 14px;outline:0;transition:border-color .15s,box-shadow .15s}\n"
        + "input:focus{border-color:var(--acc);box-shadow:0 0 0 3px rgba(124,196,255,.2)}\n"
        + ".bar{margin-top:18px;display:flex;gap:10px}\n"
        + ".bar button{flex:1;height:48px;border-radius:12px;border:1px solid var(--line);font:inherit;font-size:16px;font-weight:600;cursor:pointer;transition:border-color .15s,background .15s}\n"
        + ".sec{background:#161c27;color:var(--txt)}\n"
        + ".sec:hover:not(:disabled){border-color:var(--acc);color:var(--acc)}\n"
        + ".sec:disabled{opacity:.4;cursor:not-allowed}\n"
        + ".danger{background:#2a1416;color:var(--err);border-color:#5b2226!important}\n"
        + ".danger:hover{background:#3a1a1d}\n"
        + ".bar button:focus-visible,.pad button:focus-visible{outline:2px solid var(--acc);outline-offset:3px}\n"
        + ".hint{margin:10px 0 0;font-size:13px;color:var(--mut)}\n"
        + ".big{width:100%;height:56px;margin-top:16px;border-radius:12px;border:1px solid var(--line);font:inherit;font-size:18px;font-weight:600;cursor:pointer}\n"
        + ".big:disabled{opacity:.5;cursor:not-allowed}\n"
        + ".pad{margin-top:16px;display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:12px}\n"
        + ".pad button{height:96px;padding:0 10px;border-radius:16px;border:1px solid #2a3547;color:var(--txt);font:inherit;font-size:28px;font-weight:600;cursor:pointer;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;background-image:radial-gradient(rgba(124,196,255,.13) 1px,transparent 1.5px),linear-gradient(#182030,#121824);background-size:14px 14px,auto;transition:border-color .15s,transform .08s,color .15s}\n"
        + ".pad button:hover:not(:disabled){border-color:var(--acc);color:var(--acc)}\n"
        + ".pad button:active:not(:disabled){transform:scale(.96)}\n"
        + ".pad button:disabled{opacity:.4;cursor:not-allowed}\n"
        + ".pad button.lg{font-size:19px;line-height:1.25;white-space:normal}\n"
        + ".pad button.on{border-color:var(--busy);color:var(--busy);opacity:1}\n"
        + ".stat{display:flex;align-items:center;gap:12px;font-size:17px}\n"
        + ".dot{flex:none;width:12px;height:12px;border-radius:50%;background:var(--mut)}\n"
        + ".dot.ok{background:var(--ok);box-shadow:0 0 0 4px rgba(74,222,128,.15)}\n"
        + ".dot.err{background:var(--err);box-shadow:0 0 0 4px rgba(248,113,113,.15)}\n"
        + ".dot.busy{background:var(--busy);animation:p 1.1s ease-in-out infinite}\n"
        + "@keyframes p{0%,100%{box-shadow:0 0 0 0 rgba(251,191,36,.5)}50%{box-shadow:0 0 0 7px rgba(251,191,36,0)}}\n"
        + "@media(prefers-reduced-motion:reduce){.dot.busy{animation:none}}\n"
        + "h2{font-size:15px;font-weight:600;color:var(--mut);margin:0 0 10px}\n"
        + "ul{list-style:none;margin:0;padding:0;font-size:15px}\n"
        + "li{display:flex;gap:12px;padding:8px 0;border-top:1px solid var(--line)}\n"
        + "li:first-child{border-top:0}\n"
        + "li span{flex:none;width:64px;color:var(--mut);font-variant-numeric:tabular-nums}\n"
        + "li.ok{color:var(--ok)}\n"
        + "li.err{color:var(--err)}\n"
        + ".empty{color:var(--mut);font-size:15px}\n"
        + "a{color:var(--mut);font-size:14px}\n"
        + "a:hover{color:var(--acc)}\n"
        + ".foot{margin-top:18px;display:flex;justify-content:center;gap:22px;flex-wrap:wrap}\n"
        + "</style>\n"
        + "<div class=wrap>\n"
        + "<h1>สั่ง AMR ไปจุดที่ต้องการ</h1>\n"
        + "<div class=panel>\n"
        + "<label for=f>ชั้น</label>\n"
        + "<input id=f value='ชั้น 1' autocomplete=off>\n"
        + "<label for=a class=l2>ชื่อแอพบนแท็บเล็ต (เว้นว่าง = Industry)</label>\n"
        + "<input id=a placeholder=Industry autocomplete=off>\n"
        + "<div class=bar>\n"
        + "<button id=pull class=sec onclick=pull()>ดึงจุดจากแอพ</button>\n"
        + "</div>\n"
        + "<p class=hint>กดปุ่มจุดเพื่อสั่งหุ่นไป ถ้าจุดไม่ตรงกับในแอพ กด \"ดึงจุดจากแอพ\"</p>\n"
        + "<div class=pad id=pad></div>\n"
        + "</div>\n"
        + "<div class=panel>\n"
        + "<div class=stat><i id=d class=dot></i><span id=m>พร้อมรับคำสั่ง</span></div>\n"
        + "<button id=stop class='danger big' onclick=cancel()>ยกเลิกคำสั่ง (หยุดหุ่น)</button>\n"
        + "<p class=hint>ระบบจะกดหยุด &gt; สิ้นสุดภารกิจ &gt; ตกลง ให้เอง เสร็จแล้วสั่งจุดใหม่ได้ทันที</p>\n"
        + "</div>\n"
        + "<div class=panel>\n"
        + "<h2>คำสั่งล่าสุด</h2>\n"
        + "<ul id=l></ul>\n"
        + "<div id=e class=empty>ยังไม่มีคำสั่ง</div>\n"
        + "</div>\n"
        + "<div class=foot><a href=/apps target=_blank>รายชื่อแอพที่ติดตั้ง</a><a href=/dump target=_blank>ดูหน้าจอแท็บเล็ต (debug)</a></div>\n"
        + "</div>\n"
        + "<script>\n"
        + "var busy=false,cx=false,P=[],cm='ตรงไป';\n"
        + "function $(i){return document.getElementById(i)}\n"
        + "function now(){return new Date().toTimeString().slice(0,8)}\n"
        + "function st(c,t){$('d').className='dot '+c;$('m').textContent=t}\n"
        + "function lock(b,p){busy=b;var x=document.querySelectorAll('.pad button,#pull');for(var i=0;i<x.length;i++){x[i].disabled=b;x[i].classList.toggle('on',b&&x[i].textContent==p)}}\n"
        + "function add(t,c){var li=document.createElement('li'),a=document.createElement('span'),u=$('l');li.className=c;a.textContent=now();li.appendChild(a);li.appendChild(document.createTextNode(t));u.insertBefore(li,u.firstChild);while(u.children.length>6)u.removeChild(u.lastChild);$('e').style.display='none'}\n"
        + "function draw(){var d=$('pad');d.textContent='';P.forEach(function(p){var b=document.createElement('button');b.textContent=p;b.title=p;if(p.length>6)b.className='lg';b.onclick=function(){go(p)};d.appendChild(b)})}\n"
        + "function save(){try{localStorage.setItem('pts_'+cm,JSON.stringify(P))}catch(e){}}\n"
        + "function loadPts(){try{P=JSON.parse(localStorage.getItem('pts_'+cm)||'[]')}catch(e){P=[]}if(!P.length)P=['A','B','C','D','E','F','G'];draw()}\n"
        + "function load(){loadPts()}\n"
        + "function q(){return 'app='+encodeURIComponent($('a').value)+'&mode='+encodeURIComponent(cm)}\n"
        + "function go(p){\n"
        + "if(busy)return;\n"
        + "lock(true,p);st('busy','กำลังสั่งไปจุด '+p+' ...');\n"
        + "fetch('/go?point='+encodeURIComponent(p)+'&floor='+encodeURIComponent($('f').value)+'&'+q())\n"
        + ".then(function(x){return x.json()})\n"
        + ".then(function(j){var ok=j.ok&&String(j.msg).indexOf('sent')==0;if(j.msg!='aborted')st(ok?'ok':'err',ok?'ส่งคำสั่งไปจุด '+p+' แล้ว':'จุด '+p+': '+j.msg);add('จุด '+p+' - '+j.msg,ok?'ok':'err')})\n"
        + ".catch(function(){st('err','เชื่อมต่อแท็บเล็ตไม่ได้');add('จุด '+p+' - เชื่อมต่อแท็บเล็ตไม่ได้','err')})\n"
        + ".then(function(){if(!cx)lock(false)})\n"
        + "}\n"
        + "function cancel(){\n"
        + "if(cx)return;\n"
        + "cx=true;lock(true,'');$('stop').disabled=true;st('busy','กำลังยกเลิก (หยุด > สิ้นสุดภารกิจ > ตกลง) ...');\n"
        + "fetch('/cancel?'+q())\n"
        + ".then(function(x){return x.json()})\n"
        + ".then(function(j){st(j.ok?'ok':'err',j.ok?'ยกเลิกแล้ว สั่งต่อได้เลย':j.msg);add('ยกเลิก - '+j.msg,j.ok?'ok':'err')})\n"
        + ".catch(function(){st('err','เชื่อมต่อแท็บเล็ตไม่ได้');add('ยกเลิก - เชื่อมต่อแท็บเล็ตไม่ได้','err')})\n"
        + ".then(function(){cx=false;$('stop').disabled=false;lock(false)})\n"
        + "}\n"
        + "function pull(){\n"
        + "if(busy)return;\n"
        + "lock(true,'');st('busy','กำลังอ่านจุดจากแอพ ...');\n"
        + "fetch('/points?'+q())\n"
        + ".then(function(x){return x.json()})\n"
        + ".then(function(j){if(j.ok&&j.points&&j.points.length){P=j.points;save();draw();st('ok','พบ '+P.length+' จุดจากแอพ');add('ดึงจุด - '+P.join(', '),'ok')}else{st('err',j.msg||'ไม่พบจุด');add('ดึงจุด - '+(j.msg||'ไม่พบจุด'),'err')}})\n"
        + ".catch(function(){st('err','เชื่อมต่อแท็บเล็ตไม่ได้');add('ดึงจุด - เชื่อมต่อแท็บเล็ตไม่ได้','err')})\n"
        + ".then(function(){lock(false)})\n"
        + "}\n"
        + "load();\n"
        + "</script>";
}
