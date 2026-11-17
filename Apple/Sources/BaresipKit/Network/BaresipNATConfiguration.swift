/// NAT traversal settings for an account.
public struct BaresipNATConfiguration: Sendable, Equatable {
    /// The traversal method.
    public var mode: BaresipNATTraversal
    /// The STUN server, for example `stun:stun.example.com:3478`.
    public var stunURI: String?
    /// The TURN relay used by ICE. At most one server is supported.
    public var turnServers: [BaresipTURNServer]
    /// How long an established call may receive no RTP before it is ended.
    ///
    /// Every stream that is sent and received counts, so a video call ends when either its audio
    /// or its video stops arriving; see ``rtpTimeoutPrefersAudio``. The time on hold does not
    /// count.
    public var rtpTimeout: Duration
    /// Whether ``rtpTimeout`` watches only the audio while the call's audio is sent and received.
    ///
    /// Some devices stop sending video in the middle of a call without a new offer, for example
    /// a door station whose camera turns off while the conversation goes on. With this on, such
    /// a call stays up as long as its audio arrives; the video is watched only in a call whose
    /// audio is not sent and received. Off by default: video that stops ends the call.
    public var rtpTimeoutPrefersAudio = false
    /// Whether RTCP shares the RTP port (RFC 5761).
    ///
    /// Offered on outgoing calls and accepted when an incoming offer has it; a peer without
    /// support keeps separate ports. One port means one NAT binding and, with ICE, one
    /// component instead of two, and RTCP passes wherever RTP does. On by default.
    public var rtcpMultiplexing: Bool
    /// Whether a stream that only receives sends empty RTP packets until the peer's media arrives.
    ///
    /// Media relays such as rtpengine and some NATs forward the peer's media only after they
    /// have seen a packet from this side. A receive-only early media preview sends nothing, so
    /// without ICE it stays silent and black behind them. The packets carry an RTP header and
    /// no payload, and stop when the first packet from the peer arrives or the stream starts
    /// sending. Off by default.
    public var pinhole: Bool

    /// Creates NAT settings.
    ///
    /// - Parameters:
    ///   - mode: The traversal method.
    ///   - stunURI: The STUN server.
    ///   - turnServers: The TURN relay for ICE; at most one.
    ///   - rtpTimeout: The RTP inactivity timeout.
    ///   - rtcpMultiplexing: Whether RTCP shares the RTP port.
    ///   - pinhole: Whether receive-only streams send empty RTP packets.
    public init(mode: BaresipNATTraversal = .none, stunURI: String? = nil,
                turnServers: [BaresipTURNServer] = [],
                rtpTimeout: Duration = .seconds(30), rtcpMultiplexing: Bool = true,
                pinhole: Bool = false) {
        self.mode = mode
        self.stunURI = stunURI
        self.turnServers = turnServers
        self.rtpTimeout = rtpTimeout
        self.rtcpMultiplexing = rtcpMultiplexing
        self.pinhole = pinhole
    }
}
