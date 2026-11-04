package dev.r2hack.main;

/** The XML documents a UPnP/DLNA media renderer publishes: the device description and three service descriptions. */
final class DlnaXml {
    static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";
    static final String CM = "urn:schemas-upnp-org:service:ConnectionManager:1";

    /** Formats Android 4.4's player handles that Home Assistant and Music Assistant send. */
    // MP3 only: the board's player can't stream FLAC (or WAV/PCM) sent as an endless chunked stream, which
    // is how Music Assistant sends it, so senders are steered to MP3.
    static final String SINK_PROTOCOLS = "http-get:*:audio/mpeg:*,http-get:*:audio/mp3:*";

    static String device(String udn, String name) {
        return "<?xml version=\"1.0\"?>\n<root xmlns=\"urn:schemas-upnp-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion><device>"
                + "<deviceType>" + DEVICE_TYPE + "</deviceType>"
                + "<friendlyName>" + esc(name) + "</friendlyName>"
                + "<manufacturer>R2 Custom Software</manufacturer>"
                + "<modelName>R2 Main</modelName><modelNumber>" + esc(BuildInfo.VERSION) + "</modelNumber>"
                + "<UDN>uuid:" + udn + "</UDN><serviceList>"
                + service(AVT, "AVTransport", "avt") + service(RC, "RenderingControl", "rc")
                + service(CM, "ConnectionManager", "cm")
                + "</serviceList></device></root>";
    }

    private static String service(String type, String id, String path) {
        return "<service><serviceType>" + type + "</serviceType><serviceId>urn:upnp-org:serviceId:" + id + "</serviceId>"
                + "<SCPDURL>/dlna/" + path + ".xml</SCPDURL><controlURL>/dlna/control/" + path + "</controlURL>"
                + "<eventSubURL>/dlna/event/" + path + "</eventSubURL></service>";
    }

    static String avTransport() {
        String id = in("InstanceID", "A_ARG_TYPE_InstanceID");
        return scpd(
                action("SetAVTransportURI", id + in("CurrentURI", "AVTransportURI") + in("CurrentURIMetaData", "AVTransportURIMetaData"))
                + action("GetMediaInfo", id + out("NrTracks", "NumberOfTracks") + out("MediaDuration", "CurrentMediaDuration")
                        + out("CurrentURI", "AVTransportURI") + out("CurrentURIMetaData", "AVTransportURIMetaData")
                        + out("NextURI", "NextAVTransportURI") + out("NextURIMetaData", "NextAVTransportURIMetaData")
                        + out("PlayMedium", "PlaybackStorageMedium") + out("RecordMedium", "RecordStorageMedium")
                        + out("WriteStatus", "RecordMediumWriteStatus"))
                + action("GetTransportInfo", id + out("CurrentTransportState", "TransportState")
                        + out("CurrentTransportStatus", "TransportStatus") + out("CurrentSpeed", "TransportPlaySpeed"))
                + action("GetPositionInfo", id + out("Track", "CurrentTrack") + out("TrackDuration", "CurrentTrackDuration")
                        + out("TrackMetaData", "CurrentTrackMetaData") + out("TrackURI", "CurrentTrackURI")
                        + out("RelTime", "RelativeTimePosition") + out("AbsTime", "AbsoluteTimePosition")
                        + out("RelCount", "RelativeCounterPosition") + out("AbsCount", "AbsoluteCounterPosition"))
                + action("GetDeviceCapabilities", id + out("PlayMedia", "PossiblePlaybackStorageMedia")
                        + out("RecMedia", "PossibleRecordStorageMedia") + out("RecQualityModes", "PossibleRecordQualityModes"))
                + action("GetTransportSettings", id + out("PlayMode", "CurrentPlayMode") + out("RecQualityMode", "CurrentRecordQualityMode"))
                + action("GetCurrentTransportActions", id + out("Actions", "CurrentTransportActions"))
                + action("Stop", id) + action("Pause", id) + action("Play", id + in("Speed", "TransportPlaySpeed"))
                + action("Seek", id + in("Unit", "A_ARG_TYPE_SeekMode") + in("Target", "A_ARG_TYPE_SeekTarget")),
                var("TransportState", "string", false, "STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT")
                + var("TransportStatus", "string", false, "OK", "ERROR_OCCURRED")
                + var("TransportPlaySpeed", "string", false, "1")
                + var("PlaybackStorageMedium", "string", false, "NETWORK", "NONE")
                + var("RecordStorageMedium", "string", false, "NOT_IMPLEMENTED")
                + var("PossiblePlaybackStorageMedia", "string", false) + var("PossibleRecordStorageMedia", "string", false)
                + var("PossibleRecordQualityModes", "string", false)
                + var("CurrentPlayMode", "string", false, "NORMAL")
                + var("CurrentRecordQualityMode", "string", false, "NOT_IMPLEMENTED")
                + var("RecordMediumWriteStatus", "string", false, "NOT_IMPLEMENTED")
                + var("NumberOfTracks", "ui4", false) + var("CurrentTrack", "ui4", false)
                + var("CurrentTrackDuration", "string", false) + var("CurrentMediaDuration", "string", false)
                + var("CurrentTrackMetaData", "string", false) + var("CurrentTrackURI", "string", false)
                + var("AVTransportURI", "string", false) + var("AVTransportURIMetaData", "string", false)
                + var("NextAVTransportURI", "string", false) + var("NextAVTransportURIMetaData", "string", false)
                + var("RelativeTimePosition", "string", false) + var("AbsoluteTimePosition", "string", false)
                + var("RelativeCounterPosition", "i4", false) + var("AbsoluteCounterPosition", "i4", false)
                + var("CurrentTransportActions", "string", false)
                + var("LastChange", "string", true)
                + var("A_ARG_TYPE_SeekMode", "string", false, "REL_TIME", "ABS_TIME")
                + var("A_ARG_TYPE_SeekTarget", "string", false)
                + var("A_ARG_TYPE_InstanceID", "ui4", false));
    }

    static String renderingControl() {
        String id = in("InstanceID", "A_ARG_TYPE_InstanceID"), ch = in("Channel", "A_ARG_TYPE_Channel");
        return scpd(
                action("GetVolume", id + ch + out("CurrentVolume", "Volume"))
                + action("SetVolume", id + ch + in("DesiredVolume", "Volume"))
                + action("GetMute", id + ch + out("CurrentMute", "Mute"))
                + action("SetMute", id + ch + in("DesiredMute", "Mute")),
                "<stateVariable sendEvents=\"no\"><name>Volume</name><dataType>ui2</dataType>"
                + "<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>"
                + var("Mute", "boolean", false)
                + var("LastChange", "string", true)
                + var("A_ARG_TYPE_Channel", "string", false, "Master")
                + var("A_ARG_TYPE_InstanceID", "ui4", false));
    }

    static String connectionManager() {
        return scpd(
                action("GetProtocolInfo", out("Source", "SourceProtocolInfo") + out("Sink", "SinkProtocolInfo"))
                + action("GetCurrentConnectionIDs", out("ConnectionIDs", "CurrentConnectionIDs"))
                + action("GetCurrentConnectionInfo", in("ConnectionID", "A_ARG_TYPE_ConnectionID")
                        + out("RcsID", "A_ARG_TYPE_RcsID") + out("AVTransportID", "A_ARG_TYPE_AVTransportID")
                        + out("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo") + out("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager")
                        + out("PeerConnectionID", "A_ARG_TYPE_ConnectionID") + out("Direction", "A_ARG_TYPE_Direction")
                        + out("Status", "A_ARG_TYPE_ConnectionStatus")),
                var("SourceProtocolInfo", "string", true) + var("SinkProtocolInfo", "string", true)
                + var("CurrentConnectionIDs", "string", true)
                + var("A_ARG_TYPE_ConnectionStatus", "string", false, "OK", "ContentFormatMismatch",
                        "InsufficientBandwidth", "UnreliableChannel", "Unknown")
                + var("A_ARG_TYPE_ConnectionManager", "string", false)
                + var("A_ARG_TYPE_Direction", "string", false, "Input", "Output")
                + var("A_ARG_TYPE_ProtocolInfo", "string", false)
                + var("A_ARG_TYPE_ConnectionID", "i4", false) + var("A_ARG_TYPE_AVTransportID", "i4", false)
                + var("A_ARG_TYPE_RcsID", "i4", false));
    }

    // ---- SOAP and GENA bodies ----------------------------------------------

    static String soapResponse(String serviceType, String action, String args) {
        return "<?xml version=\"1.0\"?>\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action
                + "Response xmlns:u=\"" + serviceType + "\">" + args + "</u:" + action + "Response></s:Body></s:Envelope>";
    }

    static String soapFault(int code, String description) {
        return "<?xml version=\"1.0\"?>\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault>"
                + "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>"
                + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code + "</errorCode>"
                + "<errorDescription>" + esc(description) + "</errorDescription></UPnPError>"
                + "</detail></s:Fault></s:Body></s:Envelope>";
    }

    /** A GENA event body with the given state variables (name, value pairs). */
    static String propertySet(String... nameValue) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>\n<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">");
        for (int i = 0; i + 1 < nameValue.length; i += 2) {
            sb.append("<e:property><").append(nameValue[i]).append('>').append(esc(nameValue[i + 1]))
                    .append("</").append(nameValue[i]).append("></e:property>");
        }
        return sb.append("</e:propertyset>").toString();
    }

    static String arg(String name, Object value) {
        return "<" + name + ">" + esc(String.valueOf(value)) + "</" + name + ">";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    static String unesc(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    // ---- SCPD building blocks ----------------------------------------------

    private static String scpd(String actions, String vars) {
        return "<?xml version=\"1.0\"?>\n<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<actionList>" + actions + "</actionList><serviceStateTable>" + vars + "</serviceStateTable></scpd>";
    }

    private static String action(String name, String args) {
        return "<action><name>" + name + "</name>" + (args.isEmpty() ? "" : "<argumentList>" + args + "</argumentList>") + "</action>";
    }

    private static String in(String name, String var) {
        return "<argument><name>" + name + "</name><direction>in</direction><relatedStateVariable>" + var
                + "</relatedStateVariable></argument>";
    }

    private static String out(String name, String var) {
        return "<argument><name>" + name + "</name><direction>out</direction><relatedStateVariable>" + var
                + "</relatedStateVariable></argument>";
    }

    private static String var(String name, String type, boolean events, String... allowed) {
        StringBuilder sb = new StringBuilder("<stateVariable sendEvents=\"" + (events ? "yes" : "no") + "\"><name>" + name
                + "</name><dataType>" + type + "</dataType>");
        if (allowed.length > 0) {
            sb.append("<allowedValueList>");
            for (String a : allowed) sb.append("<allowedValue>").append(a).append("</allowedValue>");
            sb.append("</allowedValueList>");
        }
        return sb.append("</stateVariable>").toString();
    }

    private DlnaXml() {}
}
