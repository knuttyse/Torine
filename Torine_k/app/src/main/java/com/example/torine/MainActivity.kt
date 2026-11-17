package no.fibula.torine

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
//import androidx.core
import androidx.core.view.WindowCompat
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.Locale

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.RequiresPermission

class MainActivity : AppCompatActivity() {

    // ---- Geographic bounds of the background image (degrees) ----
    private val minLongitude = 11.01
    private val maxLongitude = 11.12
    private val minLatitude  = 59.00
    private val maxLatitude  = 59.05

    private val spriteScale = 2.0f
    private val apiUrl = "https://oao.no/cgi-bin/getaiscgi.py"

    // ---- Views ----
    private lateinit var sceneView: SceneView
    private lateinit var refreshButton: Button
    private lateinit var updatedTime: TextView
    private lateinit var statusList: LinearLayout
    private lateinit var ruteButton: Button


    private lateinit var skjeraButton: Button
    private lateinit var merButton: Button
    private lateinit var attribution: TextView

    private lateinit var attributionFlaticon: TextView

    // ---- Overlap offsets: 20 image-pixels in a fixed direction, per sorted index ----
    private val overlapOffsetPx = 20f
    private enum class Offset(val dx: Int, val dy: Int) { N(0, -1), E(1, 0), S(0, 1), W(-1, 0) }
    private val offsetByIndex = listOf(Offset.W, Offset.N, Offset.E, Offset.S)

    private var currentShips: List<Ship> = emptyList()

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        setContentView(R.layout.activity_main)

        sceneView      = findViewById(R.id.sceneView)
        refreshButton  = findViewById(R.id.refreshButton)
        updatedTime    = findViewById(R.id.updatedTime)
        statusList     = findViewById(R.id.statusList)

        attribution = findViewById(R.id.attribution)
        attributionFlaticon = findViewById(R.id.attributionFlaticon)

        ruteButton     = findViewById(R.id.ruteButton)

        skjeraButton = findViewById(R.id.skjeraButton)
        merButton    = findViewById(R.id.merButton)
            sceneView.spriteScale = spriteScale

        setupAttributionLinks()


        refreshButton.setOnClickListener { fetchData() }
        ruteButton.setOnClickListener {
            startActivity(android.content.Intent(this,
                RouteActivity::class.java))
        }

  /*      fetchData()
        if (savedInstanceState == null) {
            startActivity(android.content.Intent(this, RouteActivity::class.java))
        }
    */


        skjeraButton.setOnClickListener {
            startActivity(android.content.Intent(this, RemoteWebActivity::class.java).apply {
                putExtra(RemoteWebActivity.EXTRA_URL, getString(R.string.urlKortsynt))
            })
        }
        merButton.setOnClickListener {
            startActivity(android.content.Intent(this, RemoteWebActivity::class.java).apply {
                putExtra(RemoteWebActivity.EXTRA_URL, getString(R.string.urlLangsynt))
            })
        }
        fetchData()
        if (savedInstanceState == null) {
            startActivity(android.content.Intent(this,
                RouteActivity::class.java))
        }

      /*  fetchData()
        startActivity(android.content.Intent(this, RouteActivity::class.java))
        */
    }
    private fun setupAttributionLinks() {
        // "Data levert av Barentswatch" — clickable on "Barentswatch".
        val barentsText = getString(R.string.attribution)
        attribution.text = makeLinked(barentsText, "Barentswatch", "https://www.barentswatch.no")

        // "Logotegning: Flaticon" — clickable on the whole string.
        val flatText = getString(R.string.attribution_flaticon)
        attributionFlaticon.text =
            makeLinked(flatText, "Fazrian Zahrawani", "https://www.flaticon.com/free-icon/ferry-boat_12077011")
        attribution.movementMethod = LinkMovementMethod.getInstance()
        attributionFlaticon.movementMethod = LinkMovementMethod.getInstance()
    }

    /**
     * Returns a SpannableString over [fullText] with [linkText] (all occurrences
     * of it) turned into a ClickableSpan that opens [url] in the browser.
     */
    private fun makeLinked(fullText: String, linkText: String, url: String): SpannableString {
        val spannable = SpannableString(fullText)
        var start = fullText.indexOf(linkText)
        while (start >= 0) {
            val end = start + linkText.length
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url)
                            )
                        )
                    }
                },
                start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = fullText.indexOf(linkText, end)
        }
        return spannable
    }

    // ---------------------------------------------------------------------
    // Networking
    // ---------------------------------------------------------------------
    private fun fetchData() {
        refreshButton.isEnabled = false
        scope.launch {
            val json = withContext(Dispatchers.IO) { httpGet(apiUrl) }
            refreshButton.isEnabled = true
            if (json == null) {
                Toast.makeText(this@MainActivity, "Fetch failed", Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                val parsed = parseShips(json)

                // Local clock, 24h HH:mm — Android-native, no SimpleDateFormat.
                updatedTime.text = DateFormat.format("HH:mm", Date())

                val inRange = parsed
                    .filter { isInMap(it) }
                    .sortedBy { it.name }
                    .take(4)

                currentShips = inRange
                applyShips(inRange)
                buildStatusRows(inRange)
            } catch (e: Exception) {
                Log.e("Torine_k", "Parse error", e)
                Toast.makeText(this@MainActivity, "Parse error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun httpGet(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect(); body
    } catch (e: Exception) { Log.e("Torine_k", "HTTP error", e); null }

    private fun parseShips(json: String): List<Ship> {
        val arr = JSONArray(json)
        val out = ArrayList<Ship>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name", "").trim().uppercase(Locale.US)
            if (name.isEmpty()) continue
            val pname = o.optString("pname", "").ifBlank {
                name.lowercase(Locale.getDefault())
                    .replaceFirstChar { it.uppercase() }
            }
            // "delay" is seconds since the message was sent. Clamp negatives to 0.
            // If the key is missing or non-numeric, optLong defaults to 0.
// "delay" is a JSON string of seconds, always >= 0 (server guarantee).
// Parse defensively anyway: fall back to 0 on empty/malformed.
            val delayStr = o.optString("delay", "0").trim()
            val delaySeconds = delayStr.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

            out.add(Ship(
                name = name,
                pname = pname,
                latitude = o.optDouble("latitude", Double.NaN),
                longitude = o.optDouble("longitude", Double.NaN),
                trueHeading = if (o.isNull("trueHeading")) o.optDouble("courseOverGround", 0.0)
                else o.optDouble("trueHeading", 0.0),
                speedOverGround = o.optDouble("speedOverGround", 0.0),
                delaySeconds = delaySeconds
            ))        }
        return out
    }

    // ---------------------------------------------------------------------
    // Geo -> image pixel mapping
    // ---------------------------------------------------------------------
    private fun geoToImage(lon: Double, lat: Double): Pair<Float, Float> {
        val w = sceneView.backgroundWidth.toFloat()
        val h = sceneView.backgroundHeight.toFloat()
        val nx = ((lon - minLongitude) / (maxLongitude - minLongitude)).toFloat()
        val ny = ((lat - minLatitude)  / (maxLatitude  - minLatitude)).toFloat()
        return (nx * w) to ((1f - ny) * h)
    }

    private fun isInsideImage(x: Float, y: Float): Boolean =
        x >= 0f && y >= 0f && x <= sceneView.backgroundWidth && y <= sceneView.backgroundHeight

    private fun isInMap(ship: Ship): Boolean {
        if (ship.latitude.isNaN() || ship.longitude.isNaN()) return false
        val idx = currentShips.indexOfFirst { it.name == ship.name }
            .let { if (it >= 0) it else 0 }
        val off = offsetByIndex[idx % offsetByIndex.size]
        val (x, y) = geoToImage(ship.longitude, ship.latitude)
        return isInsideImage(x + off.dx * overlapOffsetPx, y + off.dy * overlapOffsetPx)
    }

    // ---------------------------------------------------------------------
    // Sprites on the map
    // ---------------------------------------------------------------------
    private fun applyShips(ships: List<Ship>) {
        val sprites = mutableListOf<SceneView.Sprite>()
        ships.forEachIndexed { index, s ->
            val moveId = resources.getIdentifier("sprite${index + 1}", "drawable", packageName)
            val restId = resources.getIdentifier("sprite${index + 1}_rest", "drawable", packageName)

            val moving = s.speedOverGround != 0.0
            val chosenId = when {
                moving && moveId != 0 -> moveId
                !moving && restId != 0 -> restId
                moveId != 0 -> moveId
                restId != 0 -> restId
                else -> 0
            }
            if (chosenId == 0) return@forEachIndexed

            val bmp = BitmapFactory.decodeResource(resources, chosenId)
            val (x, y) = geoToImage(s.longitude, s.latitude)
            val off = offsetByIndex[index % offsetByIndex.size]
            val xo = x + off.dx * overlapOffsetPx
            val yo = y + off.dy * overlapOffsetPx

            sprites.add(
                SceneView.Sprite(
                    bitmap = bmp,
                    xImage = xo,
                    yImage = yo,
                    alphaDeg = s.trueHeading.toFloat(),
                    visible = true
                )
            )
        }
        sceneView.setSprites(sprites)
    }

    // ---------------------------------------------------------------------
    // Status list — delay string comes directly from the server.
    // ---------------------------------------------------------------------
    private fun buildStatusRows(ships: List<Ship>) {
        statusList.removeAllViews()

        ships.forEachIndexed { index, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 2, 0, 2)
            }

            val icon = ImageView(this).apply {
                val restId = resources.getIdentifier(
                    "sprite${index + 1}_rest", "drawable", packageName
                )
                if (restId != 0) setImageResource(restId)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            }

            val nameTv = TextView(this).apply {
                text = s.pname
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginStart = dp(8) }
            }

            val delayTv = TextView(this).apply {
                text = formatDelay(s.delaySeconds)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
            }

            row.addView(icon)
            row.addView(nameTv)
            row.addView(delayTv)
            statusList.addView(row)
        }
    }

    /** MM:SS from a non-negative number of seconds. */
    private fun formatDelay(seconds: Long): String {
        /** MM:SS from a non-negative number of seconds; "--:--" if over 60 minutes. */
            val s = seconds.coerceAtLeast(0L)
            if (s > 3600L) return "--:--"
            return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
        }


    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}


/**
 * Returns true if the device currently has a network capable of reaching
 * the internet. On API 23+ we check the active network's capabilities
 * rather than just the legacy "network available" flag, which can be true
 * even when there's no actual internet (e.g. captive portal, no data).
 */
@RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
internal fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}