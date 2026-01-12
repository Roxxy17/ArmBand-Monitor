import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:fl_chart/fl_chart.dart'; // Jangan lupa import ini

void main() {
  runApp(const MaterialApp(
    debugShowCheckedModeBanner: false,
    home: ArmbandDashboard(),
  ));
}

class ArmbandDashboard extends StatefulWidget {
  const ArmbandDashboard({super.key});

  @override
  State<ArmbandDashboard> createState() => _ArmbandDashboardState();
}

class _ArmbandDashboardState extends State<ArmbandDashboard> with SingleTickerProviderStateMixin {
  static const methodChannel = MethodChannel('com.kalana.armband/command');
  static const eventChannel = EventChannel('com.kalana.armband/heartrate');

  // Variabel Status
  String _status = "Disconnected";
  bool _isConnected = false;

  // Data Metrik
  String _bpm = "--";
  String _battery = "--";
  String _spo2 = "--";
  String _hrv = "--";
  String _temp = "--";
  String _steps = "--";

  // Variabel Grafik
  final List<FlSpot> _bpmHistory = [];
  double _timeCounter = 0;

  // Animasi & Controller
  late AnimationController _animController;
  final TextEditingController _macController = TextEditingController(text: "F8:18:B6:64:2B:6A");

  @override
  void initState() {
    super.initState();
    _setupAnimation();
    _startListening();
  }

  void _setupAnimation() {
    // Animasi detak jantung (pulse effect)
    _animController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
      lowerBound: 0.8,
      upperBound: 1.0,
    );
  }

  void _startListening() {
    eventChannel.receiveBroadcastStream().listen((dynamic event) {
      final Map<dynamic, dynamic> data = event;
      String type = data['type'];
      String value = data['value'];

      setState(() {
        if (type == "STATUS") {
          _status = value;
          _isConnected = value == "Connected";
          if (!_isConnected) {
            _animController.stop();
            // Reset data jika putus
            _bpm = "--"; _spo2 = "--"; _hrv = "--"; _temp = "--"; _steps = "--";
            _bpmHistory.clear();
            _timeCounter = 0;
          }
        } else if (type == "BATTERY") {
          _battery = value;
        }
        // Update BPM & Grafik
        else if (type == "BPM") {
          _bpm = value;
          double? bpmVal = double.tryParse(value);

          if (bpmVal != null && bpmVal > 0) {
            // 1. Atur kecepatan animasi icon sesuai detak jantung
            _animController.repeat(reverse: true);
            _animController.duration = Duration(milliseconds: (60000 / bpmVal).round());

            // 2. Tambahkan data ke grafik
            _bpmHistory.add(FlSpot(_timeCounter++, bpmVal));

            // Batasi grafik hanya menyimpan 30 data terakhir agar tidak lag
            if (_bpmHistory.length > 30) {
              _bpmHistory.removeAt(0);
            }
          }
        } else if (type == "SPO2") {
          _spo2 = value;
        } else if (type == "HRV") {
          _hrv = value;
        } else if (type == "TEMP") {
          _temp = value;
        } else if (type == "STEPS") {
          _steps = value;
        }
      });
    }, onError: (error) {
      debugPrint("Error Stream: $error");
    });
  }

  Future<void> _connect() async {
    // Meminta izin Bluetooth & Lokasi (Wajib untuk Android 12+)
    await [Permission.bluetoothScan, Permission.bluetoothConnect, Permission.location].request();
    try {
      await methodChannel.invokeMethod('connect', {"macAddress": _macController.text.toUpperCase()});
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text("Gagal: $e")));
    }
  }

  Future<void> _disconnect() async {
    try { await methodChannel.invokeMethod('disconnect'); } catch (e) { debugPrint("Gagal disconnect: $e"); }
  }

  @override
  void dispose() {
    _animController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF1E1E2C),
      appBar: AppBar(
        title: const Text("FitMonitor Ultimate", style: TextStyle(fontWeight: FontWeight.bold, color: Colors.white)),
        backgroundColor: Colors.transparent,
        elevation: 0,
        centerTitle: true,
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            children: [
              // 1. STATUS BAR & BATTERY
              _buildStatusBar(),
              const SizedBox(height: 30),

              // 2. GRAFIK & DETAK JANTUNG UTAMA (WIDGET BARU)
              _buildLiveGraphWidget(),
              const SizedBox(height: 40),

              // 3. GRID INFO LENGKAP (SPO2, HRV, TEMP, STEPS)
              GridView.count(
                crossAxisCount: 2,
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                crossAxisSpacing: 15,
                mainAxisSpacing: 15,
                childAspectRatio: 1.5,
                children: [
                  _buildInfoCard("SPO2", _spo2, "%", Icons.water_drop, Colors.cyanAccent),
                  _buildInfoCard("HRV", _hrv, "ms", Icons.show_chart, Colors.orangeAccent),
                  _buildInfoCard("Temp", _temp, "°C", Icons.thermostat, Colors.amberAccent),
                  _buildInfoCard("Steps", _steps, "steps", Icons.directions_run, Colors.purpleAccent),
                ],
              ),

              const SizedBox(height: 30),

              // 4. TOMBOL CONNECT
              SizedBox(
                width: double.infinity,
                height: 55,
                child: ElevatedButton(
                  onPressed: _isConnected ? _disconnect : _connect,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _isConnected ? Colors.redAccent : const Color(0xFF6C63FF),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(15)),
                  ),
                  child: Text(
                    _isConnected ? "DISCONNECT DEVICE" : "CONNECT NOW",
                    style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildStatusBar() {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 20),
      decoration: BoxDecoration(
        color: const Color(0xFF2D2D44),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white10),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Row(
            children: [
              Container(
                width: 10, height: 10,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: _isConnected ? Colors.greenAccent : Colors.redAccent,
                ),
              ),
              const SizedBox(width: 10),
              Text(_status, style: const TextStyle(color: Colors.white, fontSize: 14)),
            ],
          ),
          Row(
            children: [
              Icon(Icons.battery_std, color: _battery == "--" ? Colors.grey : Colors.greenAccent, size: 20),
              const SizedBox(width: 5),
              Text("$_battery%", style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
            ],
          ),
        ],
      ),
    );
  }

  // WIDGET BARU: Menampilkan BPM + Grafik Line Chart
  Widget _buildLiveGraphWidget() {
    return Container(
      height: 220,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF2D2D44),
        borderRadius: BorderRadius.circular(20),
        boxShadow: [
          BoxShadow(color: Colors.black.withOpacity(0.3), blurRadius: 10, offset: const Offset(0, 5))
        ],
      ),
      child: Row(
        children: [
          // Bagian Kiri: Angka BPM & Icon Berdenyut
          Expanded(
            flex: 2,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                ScaleTransition(
                  scale: CurvedAnimation(parent: _animController, curve: Curves.easeInOut),
                  child: const Icon(Icons.favorite, color: Colors.redAccent, size: 40),
                ),
                const SizedBox(height: 10),
                Text(
                  _bpm,
                  style: const TextStyle(fontSize: 48, fontWeight: FontWeight.bold, color: Colors.white),
                ),
                const Text("BPM", style: TextStyle(color: Colors.white54, fontWeight: FontWeight.bold)),
              ],
            ),
          ),

          // Bagian Kanan: Grafik Real-time
          Expanded(
            flex: 3,
            child: _bpmHistory.isEmpty
                ? const Center(child: Text("Waiting Data...", style: TextStyle(color: Colors.white30)))
                : LineChart(
              LineChartData(
                gridData: const FlGridData(show: false),
                titlesData: const FlTitlesData(show: false),
                borderData: FlBorderData(show: false),
                // Skala Y (BPM) otomatis menyesuaikan visual
                minY: 40,
                maxY: 160,
                lineBarsData: [
                  LineChartBarData(
                    spots: _bpmHistory,
                    isCurved: true,
                    color: Colors.redAccent,
                    barWidth: 3,
                    isStrokeCapRound: true,
                    dotData: const FlDotData(show: false),
                    belowBarData: BarAreaData(
                      show: true,
                      color: Colors.redAccent.withOpacity(0.2),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInfoCard(String title, String value, String unit, IconData icon, Color color) {
    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFF2D2D44),
        borderRadius: BorderRadius.circular(15),
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, size: 18, color: color),
              const SizedBox(width: 5),
              Text(title, style: TextStyle(color: color, fontSize: 14, fontWeight: FontWeight.bold)),
            ],
          ),
          const SizedBox(height: 5),
          RichText(
            text: TextSpan(
              children: [
                TextSpan(text: value, style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: Colors.white)),
                TextSpan(text: " $unit", style: const TextStyle(fontSize: 12, color: Colors.grey)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}