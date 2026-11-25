# Copy to device.local.ps1 (ignored by git) and fill in. Read by deploy.ps1.
$Device = '192.168.1.x'           # your TV's IP (enable network/ADB debugging on the TV)
$Settings = @{
    subwayStops = 'A27|42 St|A,C,E'   # MTA GTFS stop id | label | routes; separate stations with ;
    sonos       = ''                  # optional Sonos IPs if SSDP discovery misses them
    calendars   = ''                  # optional: only show these calendar names
    xboxLiveId  = ''                  # optional: Xbox Live device id (Settings > System > Console info)
    xboxIp      = ''                  # optional: Xbox IP for wake-on-LAN
}
