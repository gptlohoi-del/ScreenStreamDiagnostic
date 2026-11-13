package com.example.screenstreamdiag;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/** Information available to an ordinary Android app (no root/router admin access). */
public final class NetworkSnapshot {
    private NetworkSnapshot() { }

    public static String capture(Context context) {
        StringBuilder out=new StringBuilder();
        out.append("UTC millis: ").append(System.currentTimeMillis()).append('\n');
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" API=").append(Build.VERSION.SDK_INT)
                .append(" manufacturer=").append(Build.MANUFACTURER).append(" model=").append(Build.MODEL).append('\n');
        out.append("Device build: ").append(Build.DISPLAY).append(" fingerprint=").append(Build.FINGERPRINT).append('\n');
        out.append("ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        out.append("PID: ").append(android.os.Process.myPid()).append(" elapsedRealtimeMs=")
                .append(SystemClock.elapsedRealtime()).append('\n');
        out.append("Storage: downloadsWritable=").append(Environment.getExternalStorageState()).append('\n');
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                out.append("Interface ").append(ni.getName()).append(" mtu=").append(ni.getMTU())
                        .append(" virtual=").append(ni.isVirtual()).append(" loopback=").append(ni.isLoopback()).append(" addresses=");
                for (InetAddress ip : Collections.list(ni.getInetAddresses())) out.append(ip.getHostAddress()).append(' ');
                out.append('\n');
            }
        } catch (Exception e) {out.append("Interfaces unavailable: ").append(e).append('\n');}
        try {
            ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(cm!=null){
                Network active=cm.getActiveNetwork();
                out.append("Default network: ").append(active).append('\n');
                for(Network n:cm.getAllNetworks()){
                    out.append("Network ").append(n).append(n.equals(active)?" [DEFAULT]":"").append('\n');
                    NetworkCapabilities cap=cm.getNetworkCapabilities(n);
                    if(cap!=null)out.append(" transportWifi=").append(cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                            .append(" transportCellular=").append(cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                            .append(" transportEthernet=").append(cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                            .append(" internet=").append(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                            .append(" validated=").append(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                            .append(" metered=").append(!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                            .append(" linkDownKbpsEstimate=").append(cap.getLinkDownstreamBandwidthKbps())
                            .append(" linkUpKbpsEstimate=").append(cap.getLinkUpstreamBandwidthKbps()).append('\n');
                    LinkProperties links=cm.getLinkProperties(n);
                    if(links!=null){
                        out.append(" interfaceName=").append(links.getInterfaceName()).append(" routes=");
                        for(RouteInfo route:links.getRoutes()) out.append(route).append(" | ");
                        out.append("\n addresses=");
                        for(LinkAddress la:links.getLinkAddresses())out.append(la).append(" | ");
                        out.append("\n DNS=");
                        for(InetAddress dns:links.getDnsServers())out.append(dns.getHostAddress()).append(" | ");
                        out.append("\n");
                    }
                }
            }
        } catch(Exception e) {out.append("Connectivity snapshot unavailable: ").append(e).append('\n');}
        try {
            WifiManager wm=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if(wm!=null){
                out.append("Wi-Fi enabled=").append(wm.isWifiEnabled()).append('\n');
                WifiInfo info=wm.getConnectionInfo();
                if(info!=null){
                    out.append("Wi-Fi RSSI dBm=").append(info.getRssi()).append(" linkSpeedMbps=").append(info.getLinkSpeed())
                            .append(" frequencyMHz=").append(info.getFrequency())
                            .append(" networkId=").append(info.getNetworkId()).append('\n');
                    if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){
                        out.append("Wi-Fi SSID=").append(info.getSSID()).append(" BSSID=").append(info.getBSSID()).append('\n');
                    }else out.append("Wi-Fi SSID/BSSID: not collected (optional location permission not granted).\n");
                }
                DhcpInfo dhcp=wm.getDhcpInfo();
                if(dhcp!=null){
                    out.append("DHCP IPv4=").append(ip(dhcp.ipAddress))
                            .append(" gateway/router=").append(ip(dhcp.gateway))
                            .append(" subnetMask=").append(ip(dhcp.netmask))
                            .append(" dns1=").append(ip(dhcp.dns1)).append(" dns2=").append(ip(dhcp.dns2))
                            .append(" leaseDurationSec=").append(dhcp.leaseDuration).append('\n');
                }
            }
        }catch(Exception e){out.append("Wi-Fi/DHCP snapshot unavailable: ").append(e).append('\n');}
        out.append("Router settings (band steering, DFS, QoS, client isolation, roaming): inaccessible from standard Android APIs.\n");
        out.append("Client details: RTSP request IP, remote port, User-Agent if provided, per-track transport, sent RTP and incoming RTCP if provided.\n");
        return out.toString();
    }

    private static String ip(int n){
        return String.format(Locale.US,"%d.%d.%d.%d",n&255,(n>>>8)&255,(n>>>16)&255,(n>>>24)&255);
    }
}
