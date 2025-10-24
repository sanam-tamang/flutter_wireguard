import 'package:flutter/foundation.dart';
import 'package:flutter_wireguard/flutter_wireguard_method_channel.dart';
import 'package:flutter_wireguard/flutter_wireguard_platform_interface.dart';

class WireGuardFlutter extends WireGuardFlutterInterface {
  static WireGuardFlutterInterface? __instance;
  static WireGuardFlutterInterface get _instance => __instance!;
  static WireGuardFlutterInterface get instance {
    registerWith();
    return _instance;
  }

  static void registerWith() {
    if (__instance == null) {
      if (kIsWeb) {
        throw UnsupportedError('The web platform is not supported');
      } else {
        __instance = WireGuardFlutterMethodChannel();
      }
    }
  }

  WireGuardFlutter._();

  @override
  Stream<VpnStage> get vpnStageSnapshot => _instance.vpnStageSnapshot;

  @override
  Future<void> initialize({required String interfaceName}) {
    return _instance.initialize(interfaceName: interfaceName);
  }

  @override
  Future<void> startVpn({
    required String serverAddress,
    required String wgQuickConfig,
    required String providerBundleIdentifier,
    String? notificationTitle,
    String? notificationBody,
  }) async {
    return _instance.startVpn(
      serverAddress: serverAddress,
      wgQuickConfig: wgQuickConfig,
      providerBundleIdentifier: providerBundleIdentifier,
      notificationTitle: notificationTitle,
      notificationBody: notificationBody,
    );
  }

  @override
  Future<void> stopVpn() => _instance.stopVpn();

  @override
  Future<void> refreshStage() => _instance.refreshStage();

  @override
  Future<VpnStage> stage() => _instance.stage();
}
