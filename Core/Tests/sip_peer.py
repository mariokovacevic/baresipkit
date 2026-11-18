"""Scripted SIP peer for the security stages of the core tests.

Plays the other side of a call against bsk_test_security, or against
bsk_test_call in the peer role for require-timer, over UDP and checks the
responses.

Usage: sip_peer.py SCENARIO HOST CALLEE_PORT
Scenarios: refer-ringing, fake-replace, reentrant, remove-live, stop-live,
require-timer, cancel-elsewhere, dtmf, dtmf-info, answer-on-media, hold-timeout,
video-stops-default, video-stops, video-only-timeout, one-way-audio-timeout,
audio-goes-one-way.
Exits 0 when the callee behaved as expected, else 1.
"""

import random
import re
import socket
import struct
import sys
import threading
import time


class Peer:
    def __init__(self, host, callee_port):
        self.host = host
        self.callee = (host, callee_port)
        self.sip = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sip.bind((host, 0))
        self.sip.settimeout(0.2)
        self.rtp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.rtp.bind((host, 0))
        self.video = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.video.bind((host, 0))
        self.offers_video = False
        self.audio_direction = "sendrecv"
        self.port = self.sip.getsockname()[1]
        self.uri = "sip:bob@%s:%d" % (host, callee_port)
        self.telephone_event = False
        self.last_response = ""
        self.new_dialog()

    def new_dialog(self):
        self.call_id = "%x@peer" % random.getrandbits(64)
        self.tag = "%x" % random.getrandbits(32)
        self.to_tag = None
        self.cseq = 0

    def sdp(self, direction=None):
        formats, attributes = "0", "a=rtpmap:0 PCMU/8000\r\n"
        if self.telephone_event:
            formats += " 101"
            attributes += "a=rtpmap:101 telephone-event/8000\r\na=fmtp:101 0-15\r\n"
        video = ""
        if self.offers_video:
            video = ("m=video %d RTP/AVP 96\r\na=rtpmap:96 VP8/90000\r\na=sendrecv\r\n"
                     % self.video.getsockname()[1])
        return ("v=0\r\no=- 1 1 IN IP4 %s\r\ns=-\r\nc=IN IP4 %s\r\nt=0 0\r\n"
                "m=audio %d RTP/AVP %s\r\n%sa=%s\r\n%s"
                % (self.host, self.host, self.rtp.getsockname()[1], formats, attributes,
                   direction or self.audio_direction, video))

    def request(self, method, headers=(), body="", cseq=None, branch=None,
                content_type="application/sdp"):
        if cseq is None:
            self.cseq += 1
            cseq = self.cseq
        branch = branch or "z9hG4bK%x" % random.getrandbits(48)
        to = "<%s>" % self.uri
        if self.to_tag:
            to += ";tag=%s" % self.to_tag
        lines = [
            "%s %s SIP/2.0" % (method, self.uri),
            "Via: SIP/2.0/UDP %s:%d;branch=%s;rport" % (self.host, self.port, branch),
            "Max-Forwards: 70",
            "From: <sip:alice@%s>;tag=%s" % (self.host, self.tag),
            "To: %s" % to,
            "Call-ID: %s" % self.call_id,
            "CSeq: %d %s" % (cseq, method),
            "Contact: <sip:alice@%s:%d>" % (self.host, self.port),
        ]
        lines.extend(headers)
        if body:
            lines.append("Content-Type: %s" % content_type)
        lines.append("Content-Length: %d" % len(body))
        self.sip.sendto(("\r\n".join(lines) + "\r\n\r\n" + body).encode(), self.callee)
        return branch

    def reply_ok(self, data, body=""):
        head = data.split("\r\n\r\n", 1)[0].split("\r\n")
        kept = [line for line in head[1:]
                if line.lower().startswith(("via:", "from:", "to:", "call-id:", "cseq:"))]
        if body:
            kept += ["Contact: <sip:alice@%s:%d>" % (self.host, self.port),
                     "Content-Type: application/sdp"]
        self.sip.sendto(("\r\n".join(["SIP/2.0 200 OK"] + kept
                                      + ["Content-Length: %d" % len(body)])
                         + "\r\n\r\n" + body).encode(), self.callee)

    def receive(self, seconds, accept):
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                data = self.sip.recv(65535).decode(errors="replace")
            except socket.timeout:
                continue
            if data.startswith("BYE ") or data.startswith("NOTIFY "):
                self.reply_ok(data)
            tag = re.search(r"^To:.*;tag=([^;\r\n]+)", data, re.M | re.I)
            if tag and data.startswith("SIP/2.0") and self.call_id in data:
                self.to_tag = tag.group(1)
            result = accept(data)
            if result is not None:
                return result
        return None

    def status(self, seconds, method, wanted):
        def accept(data):
            match = re.match(r"SIP/2.0 (\d+)", data)
            if not match or self.call_id not in data:
                return None
            if not re.search(r"^CSeq: \d+ %s" % method, data, re.M | re.I):
                return None
            code = int(match.group(1))
            if not wanted(code):
                return None
            self.last_response = data
            return code
        return self.receive(seconds, accept)

    def invite(self, headers=()):
        self.invite_cseq = self.cseq + 1
        self.invite_branch = self.request("INVITE", headers, self.sdp())

    def ack(self, final_error=False):
        self.request("ACK", cseq=self.invite_cseq,
                     branch=self.invite_branch if final_error else None)

    def cancel(self, headers=()):
        saved, self.to_tag = self.to_tag, None
        self.request("CANCEL", headers, cseq=self.invite_cseq, branch=self.invite_branch)
        self.to_tag = saved

    def send_rtp(self, answer, packets):
        port = re.search(r"^m=audio (\d+)", answer, re.M)
        addr = re.search(r"^c=IN IP4 ([0-9.]+)", answer, re.M)
        if not port or not addr:
            return False
        ssrc = random.getrandbits(32)
        for seq in range(packets):
            header = bytes([0x80, 0x00, seq >> 8 & 0xff, seq & 0xff])
            header += (seq * 160).to_bytes(4, "big") + ssrc.to_bytes(4, "big")
            self.rtp.sendto(header + b"\xff" * 160, (addr.group(1), int(port.group(1))))
            time.sleep(0.02)
        return True

    def bye_from_callee(self, seconds):
        return self.receive(seconds, lambda data: True if data.startswith("BYE ") else None)


def establish(peer):
    peer.invite()
    if peer.status(10, "INVITE", lambda code: code == 200) != 200:
        return False
    peer.ack()
    return True


def refer_ringing(peer):
    peer.invite()
    if peer.status(10, "INVITE", lambda code: code == 180) != 180:
        return "no 180"
    peer.request("REFER", ["Refer-To: <sip:mallory@%s:5099>" % peer.host])
    code = peer.status(3, "REFER", lambda code: code >= 200)
    if code != 403:
        return "REFER answered with %s" % code
    if peer.status(10, "INVITE", lambda code: code == 200) != 200:
        return "the call did not answer after the REFER"
    peer.ack()
    time.sleep(0.5)
    peer.request("BYE")
    if peer.status(3, "BYE", lambda code: code >= 200) != 200:
        return "BYE not accepted"
    return None


def fake_replace(peer):
    if not establish(peer):
        return "first call not answered"
    time.sleep(0.5)
    old = "%s;to-tag=%s;from-tag=%s" % (peer.call_id, peer.to_tag, peer.tag)
    peer.request("BYE", ['Reason: SIP;cause=200;text="%s replaced"' % old])
    if peer.status(3, "BYE", lambda code: code >= 200) != 200:
        return "BYE not accepted"

    peer.new_dialog()
    peer.invite(["Replaces: unknown@peer;to-tag=a;from-tag=b"])
    if peer.status(3, "INVITE", lambda code: code >= 300) != 481:
        return "an unknown Replaces was not answered with 481"
    peer.ack(final_error=True)

    peer.new_dialog()
    peer.invite()
    if peer.status(10, "INVITE", lambda code: code == 180) != 180:
        return "second call does not ring"
    if peer.status(2.5, "INVITE", lambda code: code == 200) == 200:
        return "second call was answered without the user"
    peer.cancel()
    peer.status(3, "INVITE", lambda code: code == 487)
    peer.ack(final_error=True)
    return None


def reentrant(peer):
    peer.invite()
    code = peer.status(10, "INVITE", lambda code: code >= 300)
    if code != 486:
        return "first call answered with %s" % code
    peer.ack(final_error=True)

    peer.new_dialog()
    if not establish(peer):
        return "second call not answered"
    time.sleep(0.5)
    peer.request("BYE")
    if peer.status(3, "BYE", lambda code: code >= 200) != 200:
        return "BYE not accepted"
    return None


def final_response(peer, seconds):
    def accept(data):
        match = re.match(r"SIP/2.0 (\d+)", data)
        if not match or peer.call_id not in data or int(match.group(1)) < 200:
            return None
        return data if re.search(r"^CSeq: \d+ INVITE", data, re.M | re.I) else None
    return peer.receive(seconds, accept)


def require_timer(peer):
    peer.invite(("Supported: timer", "Session-Expires: 30"))
    answer = final_response(peer, 10)
    if not answer or not answer.startswith("SIP/2.0 422"):
        return "an interval below Min-SE was not answered with 422"
    peer.ack(final_error=True)
    if not re.search(r"^Min-SE: 90\r?$", answer, re.M | re.I):
        return "the 422 names no Min-SE of 90"

    peer.new_dialog()
    peer.invite(("Supported: timer", "Require: timer", "Session-Expires: 90;refresher=uac"))
    answer = final_response(peer, 10)
    if not answer or not answer.startswith("SIP/2.0 200"):
        return "an INVITE that requires timers was not answered with 200"
    peer.ack()
    if not re.search(r"^Session-Expires: 90;refresher=uac\r?$", answer, re.M | re.I):
        return "the 200 has no Session-Expires with the caller as refresher"
    if not re.search(r"^Require:.*\btimer\b", answer, re.M | re.I):
        return "the 200 does not require timer"
    if not peer.send_rtp(answer, 50):
        return "the 200 has no audio address"
    peer.request("BYE")
    if peer.status(5, "BYE", lambda code: code == 200) != 200:
        return "BYE not answered"
    return None


def cancel_elsewhere(peer):
    reasons = (
        ('Reason: SIP;cause=200;text="Call completed elsewhere"',),
        ('Reason: SIP ;cause=603 ;text="Decline"',),
        ('Reason: SIP;cause=487;text="ORIGINATOR_CANCEL"',),
        (),
    )
    for headers in reasons:
        peer.new_dialog()
        peer.invite()
        if peer.status(10, "INVITE", lambda code: code == 180) != 180:
            return "the INVITE did not ring"
        peer.cancel(headers)
        if peer.status(5, "CANCEL", lambda code: code == 200) != 200:
            return "CANCEL not answered with 200"
        if peer.status(5, "INVITE", lambda code: code == 487) != 487:
            return "the INVITE did not end with 487"
        peer.ack(final_error=True)
        time.sleep(0.3)
    return None


def ended_by_callee(peer):
    if not establish(peer):
        return "call not answered"
    if not peer.bye_from_callee(10):
        return "no BYE from the callee"
    return None


def media_address(response):
    body = response.split("\r\n\r\n", 1)[-1]
    host = re.search(r"^c=IN IP4 (\S+)", body, re.M)
    port = re.search(r"^m=audio (\d+)", body, re.M)
    if not host or not port:
        return None
    return host.group(1), int(port.group(1))


def send_telephone_event(peer, address, digit):
    ssrc = random.getrandbits(32)
    timestamp = random.getrandbits(32)
    packets = [(i == 0, False, 400 * (i + 1)) for i in range(3)]
    packets += [(False, True, 1600)] * 3
    for seq, (marker, end, duration) in enumerate(packets, random.getrandbits(15)):
        header = struct.pack("!BBHII", 0x80, (0x80 if marker else 0) | 101,
                             seq & 0xFFFF, timestamp, ssrc)
        payload = struct.pack("!BBH", digit, (0x80 if end else 0) | 10, duration)
        peer.rtp.sendto(header + payload, address)
        time.sleep(0.02)


def answer_on_media(peer):
    peer.invite()
    if peer.status(10, "INVITE", lambda code: code == 183) != 183:
        return "no 183 with early media"
    if not peer.send_rtp(peer.last_response, 10):
        return "no audio address in the 183"
    if peer.status(5, "INVITE", lambda code: code >= 200) != 200:
        return "the call was not answered when audio arrived"
    peer.ack()
    time.sleep(0.3)
    peer.request("BYE")
    if peer.status(3, "BYE", lambda code: code >= 200) != 200:
        return "BYE not accepted"
    return None


class RtpStream(threading.Thread):
    def __init__(self, sock, address, payload_type, clock_step, payload, interval):
        super().__init__(daemon=True)
        self.sock = sock
        self.address = address
        self.payload_type = payload_type
        self.clock_step = clock_step
        self.payload = payload
        self.interval = interval
        self.sending = threading.Event()
        self.sending.set()
        self.stopped = threading.Event()

    def run(self):
        ssrc = random.getrandbits(32)
        timestamp = random.getrandbits(32)
        seq = 0
        while not self.stopped.is_set():
            if self.sending.is_set():
                header = struct.pack("!BBHII", 0x80, self.payload_type, seq & 0xFFFF,
                                     (timestamp + self.clock_step * seq) & 0xFFFFFFFF, ssrc)
                self.sock.sendto(header + self.payload, self.address)
                seq += 1
            time.sleep(self.interval)


def audio_stream(peer, address):
    return RtpStream(peer.rtp, address, 0, 160, b"\xff" * 160, 0.02)


def video_stream(peer, address):
    return RtpStream(peer.video, address, 0x80 | 96, 6000, b"\x10" + bytes(200), 0.066)


def video_address(response):
    body = response.split("\r\n\r\n", 1)[-1]
    host = re.search(r"^c=IN IP4 (\S+)", body, re.M)
    port = re.search(r"^m=video (\d+)", body, re.M)
    if not host or not port or port.group(1) == "0":
        return None
    return host.group(1), int(port.group(1))


def reinvite(peer, seconds):
    return peer.receive(seconds, lambda data: data if data.startswith("INVITE ") else None)


def end_call(peer):
    peer.request("BYE")
    if peer.status(3, "BYE", lambda code: code >= 200) != 200:
        return "BYE not accepted"
    return None


def hold_timeout(peer):
    if not establish(peer):
        return "call not answered"
    audio = audio_stream(peer, media_address(peer.last_response))
    audio.start()
    hold = reinvite(peer, 10)
    if not hold or "a=sendonly" not in hold:
        return "the callee did not put the call on hold"
    audio.sending.clear()
    peer.reply_ok(hold, peer.sdp("recvonly"))
    resume = reinvite(peer, 10)
    if not resume or "a=sendonly" in resume:
        return "the callee did not resume the call"
    peer.reply_ok(resume, peer.sdp())
    time.sleep(1.5)
    audio.sending.set()
    if peer.bye_from_callee(3):
        return "the callee ended the call after the hold"
    audio.stopped.set()
    return end_call(peer)


def send_video_for_a_second(peer):
    address = video_address(peer.last_response)
    if not address:
        return "no video in the answer"
    video = video_stream(peer, address)
    video.start()
    time.sleep(1)
    video.stopped.set()
    return None


def video_stops(peer, ends_the_call=False):
    peer.offers_video = True
    if not establish(peer):
        return "call not answered"
    audio = audio_stream(peer, media_address(peer.last_response))
    audio.start()
    failure = send_video_for_a_second(peer)
    if failure:
        return failure
    ended = peer.bye_from_callee(6)
    audio.stopped.set()
    if ends_the_call:
        return None if ended else "the callee kept the call when the video stopped"
    if ended:
        return "the callee ended the call when the video stopped"
    return end_call(peer)


def video_stops_default(peer):
    return video_stops(peer, ends_the_call=True)


def ends_when_video_stops(peer, answered_audio, complaint):
    peer.offers_video = True
    if not establish(peer):
        return "call not answered"
    if not re.search(r"^m=audio.*?^a=%s" % answered_audio, peer.last_response, re.M | re.S):
        return "the audio of the answer is not %s" % answered_audio
    failure = send_video_for_a_second(peer)
    if failure:
        return failure
    if not peer.bye_from_callee(8):
        return complaint
    return None


def video_only_timeout(peer):
    return ends_when_video_stops(
        peer, "inactive", "the callee kept the call although its only media stopped")


def one_way_audio_timeout(peer):
    peer.audio_direction = "sendonly"
    return ends_when_video_stops(
        peer, "recvonly", "the callee kept the call although nothing it watches arrived")


def reinvites_until_bye(peer, seconds):
    def accept(data):
        if data.startswith("BYE "):
            return "bye"
        if re.match(r"SIP/2.0 200", data) and re.search(r"^CSeq: \d+ INVITE", data, re.M | re.I):
            return "answered"
        return None

    deadline = time.time() + seconds
    while time.time() < deadline:
        peer.invite()
        result = peer.receive(1, accept)
        if result == "answered":
            peer.ack()
            result = peer.receive(1, accept)
        if result == "bye":
            return True
    return False


def audio_goes_one_way(peer):
    peer.offers_video = True
    if not establish(peer):
        return "call not answered"
    audio = audio_stream(peer, media_address(peer.last_response))
    audio.start()
    failure = send_video_for_a_second(peer)
    if failure:
        return failure
    if peer.bye_from_callee(4):
        return "the callee ended the call when the video stopped"
    peer.audio_direction = "sendonly"
    ended = reinvites_until_bye(peer, 10)
    audio.stopped.set()
    return None if ended else "the callee kept the call after its audio became one-way"


def receive_telephone_event(peer, payload_type, digit, seconds):
    peer.rtp.settimeout(0.2)
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            data = peer.rtp.recv(2048)
        except socket.timeout:
            continue
        if len(data) >= 16 and data[1] & 0x7F == payload_type and data[12] == digit:
            return True
    return False


def dtmf(peer):
    peer.telephone_event = True
    if not establish(peer):
        return "call not answered"
    address = media_address(peer.last_response)
    if not address:
        return "no audio address in the answer"
    payload_type = re.search(r"^a=rtpmap:(\d+) telephone-event/", peer.last_response, re.M)
    if not payload_type:
        return "telephone-event not in the answer"
    time.sleep(0.5)
    peer.request("INFO", body="Signal=5\r\nDuration=100\r\n",
                 content_type="application/dtmf-relay")
    code = peer.status(3, "INFO", lambda code: code >= 200)
    if code != 200:
        return "INFO answered with %s" % code
    time.sleep(0.5)
    send_telephone_event(peer, address, 7)
    if not receive_telephone_event(peer, int(payload_type.group(1)), 8, 5):
        return "no telephone-event for 8 from the callee"
    time.sleep(0.3)
    peer.request("BYE")
    if peer.status(3, "BYE", lambda code: code >= 200) != 200:
        return "BYE not accepted"
    return None


def dtmf_info(peer):
    if not establish(peer):
        return "call not answered"

    def accept(data):
        if not data.startswith("INFO "):
            return None
        peer.reply_ok(data)
        if not re.search(r"^Content-Type: *application/dtmf-relay", data, re.M | re.I):
            return "INFO without application/dtmf-relay"
        if not re.search(r"^Signal= *4", data.split("\r\n\r\n", 1)[-1], re.M):
            return "INFO without Signal=4"
        return ""

    failure = peer.receive(5, accept)
    if failure is None:
        return "no SIP INFO for 4 from the callee"
    if failure:
        return failure
    time.sleep(0.3)
    peer.request("BYE")
    if peer.status(3, "BYE", lambda code: code >= 200) != 200:
        return "BYE not accepted"
    return None


SCENARIOS = {
    "refer-ringing": refer_ringing,
    "fake-replace": fake_replace,
    "reentrant": reentrant,
    "remove-live": ended_by_callee,
    "stop-live": ended_by_callee,
    "require-timer": require_timer,
    "cancel-elsewhere": cancel_elsewhere,
    "dtmf": dtmf,
    "dtmf-info": dtmf_info,
    "answer-on-media": answer_on_media,
    "hold-timeout": hold_timeout,
    "video-stops-default": video_stops_default,
    "video-stops": video_stops,
    "video-only-timeout": video_only_timeout,
    "one-way-audio-timeout": one_way_audio_timeout,
    "audio-goes-one-way": audio_goes_one_way,
}


def main():
    scenario, host, port = sys.argv[1], sys.argv[2], int(sys.argv[3])
    failure = SCENARIOS[scenario](Peer(host, port))
    print("peer %s: %s" % (scenario, "PASS" if failure is None else "FAIL (%s)" % failure))
    return 0 if failure is None else 1


if __name__ == "__main__":
    sys.exit(main())
