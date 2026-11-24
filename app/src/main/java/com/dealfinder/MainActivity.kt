package com.dealfinder

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URLEncoder

data class Deal(val store: String, val title: String, val price: Double, val old: Double, val disc: Double, val url: String)

const val AMAZON_JS = """(function(){var o=[];window.scrollTo(0,document.body.scrollHeight);
document.querySelectorAll('div[data-component-type="s-search-result"]').forEach(function(c){
var t=c.querySelector('h2'),p=c.querySelector('.a-price:not(.a-text-price) .a-offscreen'),
od=c.querySelector('.a-price.a-text-price .a-offscreen'),a=c.querySelector('h2 a')||c.querySelector('a.a-link-normal');
if(!t||!p||!a)return;
o.push({t:t.innerText.trim().slice(0,90),p:p.textContent,o:od?od.textContent:'',pct:0,u:a.href.split('?')[0]});});
return JSON.stringify(o);})()"""

const val NOON_JS = """(function(){var o=[];document.querySelectorAll('a[href*="/p/"],a[href*="/N"]').forEach(function(a){
var L=a.innerText.split('\n').map(function(s){return s.trim()}).filter(Boolean);
if(L.length<2)return;
var n=L.filter(function(l){return /^(AED\s*)?[\d,]+(\.\d+)?${'$'}/i.test(l)}).map(function(l){return parseFloat(l.replace(/[^\d.]/g,''))});
if(!n.length)return;
var pct=0;L.forEach(function(l){var m=l.match(/(\d+)\s*%/);if(m)pct=parseFloat(m[1])});
var t=L.reduce(function(x,y){return y.length>x.length?y:x});
o.push({t:t.slice(0,90),p:String(Math.min.apply(null,n)),o:n.length>1?String(Math.max.apply(null,n)):'',pct:pct,u:a.href});});
return JSON.stringify(o);})()"""

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var list: ListView
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Triple<String, String, String>>() // store, url, js
    private val found = LinkedHashMap<String, Deal>()
    private val shown = mutableListOf<Deal>()
    private var minDisc = 0.0
    private var maxPrice = Double.MAX_VALUE
    private var waiting = false
    private var gen = 0

    private fun num(s: String) = Regex("""\d[\d,]*\.?\d*""").find(s)?.value?.replace(",", "")?.toDoubleOrNull() ?: 0.0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 16) }
        val q = EditText(this).apply { hint = "Search / category (e.g. headphones)"; setSingleLine() }
        val d = EditText(this).apply { hint = "Min discount % (e.g. 70)"; inputType = InputType.TYPE_CLASS_NUMBER }
        val p = EditText(this).apply { hint = "Max price AED (optional, e.g. 1)"; inputType = InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_CLASS_NUMBER }
        val btn = Button(this).apply { text = "Search deals" }
        status = TextView(this).apply { setPadding(0, 12, 0, 12) }
        list = ListView(this)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                if (!waiting) return
                waiting = false
                val g = gen
                handler.postDelayed({ if (g == gen) extract() }, 3500)
            }
        }
        listOf(q, d, p, btn, status).forEach { root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 2)) // hidden scraper
        setContentView(root)

        list.setOnItemClickListener { _, _, i, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(shown[i].url))) }
        btn.setOnClickListener {
            val kw = q.text.toString().trim()
            if (kw.isEmpty()) return@setOnClickListener
            minDisc = d.text.toString().toDoubleOrNull() ?: 0.0
            maxPrice = p.text.toString().toDoubleOrNull() ?: Double.MAX_VALUE
            found.clear(); shown.clear(); render(); queue.clear(); gen++
            val e = URLEncoder.encode(kw, "UTF-8")
            queue.add(Triple("Amazon.ae", "https://www.amazon.ae/s?k=$e", AMAZON_JS))
            queue.add(Triple("Amazon.ae", "https://www.amazon.ae/s?k=$e&page=2", AMAZON_JS))
            queue.add(Triple("Noon", "https://www.noon.com/uae-en/search/?q=$e", NOON_JS))
            next()
        }
    }

    private var current: Triple<String, String, String>? = null

    private fun next() {
        val item = queue.removeFirstOrNull()
        if (item == null) { status.text = "Done: ${shown.size} deals"; return }
        current = item
        status.text = "Searching ${item.first}… (${shown.size} found)"
        waiting = true
        val g = gen
        web.loadUrl(item.second)
        handler.postDelayed({ if (g == gen && waiting) { waiting = false; extract() } }, 25000)
    }

    private fun extract() {
        val cur = current ?: return
        val g = gen
        web.evaluateJavascript(cur.third) { r ->
            if (g != gen) return@evaluateJavascript
            try {
                val s = JSONTokener(r).nextValue()
                if (s is String) {
                    val arr = JSONArray(s)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val price = num(o.getString("p")); val old = num(o.getString("o"))
                        val disc = if (old > price && price >= 0) (old - price) / old * 100 else o.optDouble("pct", 0.0)
                        val u = o.getString("u")
                        found[u] = Deal(cur.first, o.getString("t"), price, old, Math.round(disc * 10) / 10.0, u)
                    }
                }
            } catch (_: Exception) {}
            shown.clear()
            shown.addAll(found.values.filter { it.disc >= minDisc && it.price <= maxPrice }.sortedByDescending { it.disc })
            render()
            next()
        }
    }

    private fun render() {
        val rows = shown.map { "-${it.disc}%  AED ${it.price}" + (if (it.old > 0) " (was ${it.old})" else "") + "\n[${it.store}] ${it.title}" }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }
}
