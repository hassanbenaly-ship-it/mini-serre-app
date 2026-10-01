package ma.benaly.miniserre

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF163D38), secondary = Color(0xFFB9E98E))) {
                Dashboard()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dashboard(vm: MainViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(false) }
    val manual = (s.control?.mode ?: s.live?.mode) == "manuel"

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("Mini-serre connectée"); Text(
                if (s.lastChange > 0) "Dernier changement : " + DateFormat.getTimeInstance().format(Date(s.lastChange)) else "En attente de données",
                style = MaterialTheme.typography.labelSmall) } },
            actions = { TextButton(onClick = { settings = true }) { Text("Réglages") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            s.error?.let { Banner(it, error = true) }
            if (s.error == null && s.live == null) Banner("Aucune mesure reçue. Démarrez la simulation Wokwi.")
            if (s.error == null && s.live != null && s.control == null) Banner("Initialisez /serre/control dans Firebase pour activer les commandes.")

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("🌡️ Température", s.live?.temperature, "°C", 1, Modifier.weight(1f))
                Metric("💧 Air", s.live?.humidite, "%", 0, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("🌱 Sol", s.live?.sol, "%", 0, Modifier.weight(1f))
                Metric("☀️ Lumière", s.live?.luminosite, "%", 0, Modifier.weight(1f))
            }

            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mode de contrôle", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((id, label) in listOf("auto" to "Automatique", "manuel" to "Manuel")) {
                        FilterChip(selected = (s.control?.mode ?: s.live?.mode) == id,
                            onClick = { vm.send("mode", id) }, enabled = s.control != null && !s.busy, label = { Text(label) })
                    }
                }
                Text("En mode automatique, l’ESP32 décide selon les mesures.", style = MaterialTheme.typography.bodySmall)
            } }

            Card { Column(Modifier.padding(16.dp)) {
                Text("Actionneurs", style = MaterialTheme.typography.titleMedium)
                for ((key, label, on) in listOf(
                    Triple("fan", "Ventilateur", s.live?.fan), Triple("pump", "Pompe", s.live?.pump), Triple("led", "Éclairage LED", s.live?.led))) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label)
                        Switch(checked = on == true, enabled = manual && s.live != null && !s.busy, onCheckedChange = { vm.send(key, it) })
                    }
                }
            } }

            Text("Historique", style = MaterialTheme.typography.titleMedium)
            Chart("Température (°C)", s.history.map { it.ts to it.temperature }, Color(0xFFDB7450))
            Chart("Humidité de l’air (%)", s.history.map { it.ts to it.humidite }, Color(0xFF598EC6))
            Chart("Humidité du sol (%)", s.history.map { it.ts to it.sol }, Color(0xFF398B65))
            Chart("Luminosité (%)", s.history.map { it.ts to it.luminosite }, Color(0xFFD8A638))
        }
    }

    if (settings) {
        var url by remember { mutableStateOf(s.origin) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { settings = false },
            title = { Text("Connexion Firebase") },
            text = { Column {
                OutlinedTextField(url, { url = it }, label = { Text("URL Realtime Database") }, singleLine = true)
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = { err = vm.connect(url); if (err == null) settings = false }) { Text("Connecter") } },
            dismissButton = { TextButton(onClick = { settings = false }) { Text("Annuler") } })
    }
}

@Composable
fun Banner(text: String, error: Boolean = false) = Card(colors = CardDefaults.cardColors(
    containerColor = if (error) Color(0xFFFCE9E5) else Color(0xFFFFF0D8))) { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }

@Composable
fun Metric(label: String, v: Double?, unit: String, digits: Int, modifier: Modifier) = Card(modifier) {
    Column(Modifier.padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(if (v == null) "—" else "%.${digits}f %s".format(v, unit), style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
fun Chart(title: String, data: List<Pair<Long, Double?>>, color: Color) = Card {
    Column(Modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        val pts = data.mapNotNull { (_, v) -> v }
        if (pts.size < 2) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Text("En attente de mesures historiques") }
        } else {
            val lo = pts.min(); val hi = pts.max(); val span = (hi - lo).coerceAtLeast(1.0)
            Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp)) {
                val path = Path()
                pts.forEachIndexed { i, v ->
                    val p = Offset(size.width * i / (pts.size - 1), size.height * (1f - ((v - lo) / span).toFloat()))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, color, style = Stroke(width = 4f))
            }
            Text("min %.1f · max %.1f".format(lo, hi), style = MaterialTheme.typography.labelSmall)
        }
    }
}
