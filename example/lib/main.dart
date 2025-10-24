import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_wireguard/flutter_wireguard.dart';
import 'package:flutter_wireguard/flutter_wireguard_platform_interface.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(
    MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      home: const WireGuardHome(),
    ),
  );
}

class WireGuardHome extends StatefulWidget {
  const WireGuardHome({super.key});

  @override
  State<WireGuardHome> createState() => _WireGuardHomeState();
}

class _WireGuardHomeState extends State<WireGuardHome> {
  final wireguard = WireGuardFlutter.instance;
  final String interfaceName = 'SecureTunnel';
  //get these values from your server or in order to test get from fash.ssh
  //
  final String conf = '''
[Interface]
PrivateKey = yourprivatekey
Address = 10.67.69.50/31
DNS = 8.8.8.8

[Peer]
PublicKey = yourpublickey
PresharedKey = presharekey
Endpoint = sanamtest.com.wg-sg1.hostip.co:443
AllowedIPs = 0.0.0.0/0,::/0
''';

  VpnStage _status = VpnStage.noConnection;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    initNotificationPermission();
    _initializeAndCheckStatus();
  }

  void initNotificationPermission() async {
    await Permission.notification.request();
  }

  Future<void> _initializeAndCheckStatus() async {
    try {
      await wireguard.initialize(interfaceName: interfaceName);
      final stage = await wireguard.stage();
      if (mounted) {
        setState(() {
          _status = stage;
        });
      }
    } catch (e) {
      if (mounted) {
        _showMessage('Init failed: $e');
      }
    }
  }

  Future<void> _connect() async {
    if (_isLoading) return;
    setState(() {
      _isLoading = true;
    });

    try {
      await wireguard.startVpn(
        serverAddress: '167.235.55.239:51820',
        wgQuickConfig: conf,
        providerBundleIdentifier: 'com.example.demovpn.WGExtension',
        // Custom notification title and body
        notificationTitle: 'Your internet is private',
        notificationBody: 'Tap to open demo app settings',
      );
      await Future.delayed(const Duration(milliseconds: 500));
      final stage = await wireguard.stage();
      if (mounted) {
        setState(() {
          _status = stage;
          _isLoading = false;
        });
        _showMessage('Connected securely!');
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
        _showMessage('Connection failed: $e');
      }
    }
  }

  Future<void> _disconnect() async {
    if (_isLoading) return;
    setState(() {
      _isLoading = true;
    });

    try {
      await wireguard.stopVpn();
      await Future.delayed(const Duration(milliseconds: 300));
      final stage = await wireguard.stage();
      if (mounted) {
        setState(() {
          _status = stage;
          _isLoading = false;
        });
        _showMessage('Disconnected');
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
        _showMessage('Disconnection failed: $e');
      }
    }
  }

  void _showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).hideCurrentSnackBar();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), duration: const Duration(seconds: 3)),
    );
  }

  @override
  Widget build(BuildContext context) {
    Color statusColor;
    String statusText;

    switch (_status) {
      case VpnStage.connected:
        statusColor = Colors.green;
        statusText = '🔒 Secure Connection Active';
        break;
      case VpnStage.disconnected:
        statusColor = Colors.red;
        statusText = '⚠️ Disconnected';
        break;
      case VpnStage.connecting:
        statusColor = Colors.orange;
        statusText = '🔄 Connecting...';
        break;
      case VpnStage.disconnecting:
        statusColor = Colors.orange;
        statusText = '🔄 Disconnecting...';
        break;
      default:
        statusColor = Colors.grey;
        statusText = '❓ Status: $_status';
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('Secure Tunnel'),
        centerTitle: true,
        backgroundColor: Theme.of(context).colorScheme.primary,
      ),
      body: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: statusColor.withOpacity(0.1),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: statusColor),
              ),
              child: Text(
                statusText,
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w500,
                  color: statusColor,
                ),
                textAlign: TextAlign.center,
              ),
            ),
            const SizedBox(height: 40),

            SizedBox(
              width: double.infinity,
              height: 56,
              child: ElevatedButton.icon(
                onPressed: _isLoading
                    ? null
                    : (_status == VpnStage.connected ? _disconnect : _connect),
                icon: Icon(
                  _status == VpnStage.connected
                      ? Icons.power_settings_new
                      : Icons.lock,
                  size: 20,
                ),
                label: Text(
                  _status == VpnStage.connected
                      ? 'Disconnect'
                      : 'Connect Securely',
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                style: ElevatedButton.styleFrom(
                  backgroundColor: _status == VpnStage.connected
                      ? Colors.redAccent
                      : Theme.of(context).colorScheme.primary,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 30),

            const Text(
              'Your internet traffic is encrypted end-to-end.\nA persistent notification will appear while active.',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 14, color: Colors.grey),
            ),
          ],
        ),
      ),
    );
  }
}
