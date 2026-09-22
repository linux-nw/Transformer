// net.js — real device-to-device networking for Transformer.
//
// No server, no STUN/TURN: every RTCPeerConnection here is created with
// iceServers: [] so ICE can only produce "host" candidates (local network
// interfaces). That makes it structurally impossible for a connection to
// succeed over the internet — it only connects when both devices share a
// reachable LAN. Pairing (the one moment a shared secret + SDP has to cross
// from one device to the other) happens by hand, via QR code, never over
// any network.
//
// Every chat message and file chunk is additionally encrypted with
// AES-256-GCM using a key that is generated once at pairing time and only
// ever travels inside that first QR code — so even though WebRTC data
// channels are already DTLS-encrypted in transit, the payload itself is
// opaque to anything but the two paired devices.
(function () {
  'use strict';

  var PEERS_KEY = 'transformer.peers.v1';
  var OUTBOX_KEY = 'transformer.outbox.v1';
  var FILE_CHUNK_BYTES = 48 * 1024;
  var BUFFERED_AMOUNT_LOW = 256 * 1024;

  // ---------------------------------------------------------------- utils

  function randomId() {
    var bytes = crypto.getRandomValues(new Uint8Array(9));
    return b64FromBytes(bytes).replace(/[+/=]/g, '');
  }

  function b64FromBytes(bytes) {
    var bin = '';
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin);
  }
  function bytesFromB64(str) {
    var bin = atob(str);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }

  function emitter() {
    var handlers = {};
    return {
      on: function (ev, cb) {
        (handlers[ev] = handlers[ev] || []).push(cb);
        return function off() {
          handlers[ev] = (handlers[ev] || []).filter(function (h) { return h !== cb; });
        };
      },
      emit: function (ev) {
        var args = Array.prototype.slice.call(arguments, 1);
        (handlers[ev] || []).slice().forEach(function (h) {
          try { h.apply(null, args); } catch (e) { console.error('net.js handler error', ev, e); }
        });
      },
    };
  }

  // -------------------------------------------------------------- storage

  function loadPeers() {
    try { return JSON.parse(localStorage.getItem(PEERS_KEY) || '[]'); } catch (e) { return []; }
  }
  function savePeers(list) {
    try { localStorage.setItem(PEERS_KEY, JSON.stringify(list)); } catch (e) {}
  }
  function loadOutbox() {
    try { return JSON.parse(localStorage.getItem(OUTBOX_KEY) || '{}'); } catch (e) { return {}; }
  }
  function saveOutbox(map) {
    try { localStorage.setItem(OUTBOX_KEY, JSON.stringify(map)); } catch (e) {}
  }

  // --------------------------------------------------------------- crypto

  function generateKey() {
    return crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt']);
  }
  function exportKeyB64(key) {
    return crypto.subtle.exportKey('raw', key).then(function (raw) { return b64FromBytes(new Uint8Array(raw)); });
  }
  function importKeyB64(b64) {
    return crypto.subtle.importKey('raw', bytesFromB64(b64), { name: 'AES-GCM' }, true, ['encrypt', 'decrypt']);
  }
  function encryptJson(key, obj) {
    var iv = crypto.getRandomValues(new Uint8Array(12));
    var data = new TextEncoder().encode(JSON.stringify(obj));
    return crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv }, key, data).then(function (cipher) {
      return { iv: b64FromBytes(iv), data: b64FromBytes(new Uint8Array(cipher)) };
    });
  }
  function decryptJson(key, envelope) {
    var iv = bytesFromB64(envelope.iv);
    var cipher = bytesFromB64(envelope.data);
    return crypto.subtle.decrypt({ name: 'AES-GCM', iv: iv }, key, cipher).then(function (plain) {
      return JSON.parse(new TextDecoder().decode(plain));
    });
  }

  // ----------------------------------------------------------------- SDP
  // Trim optional trailing fields off ICE candidate lines (generation,
  // ufrag, network-cost, ...) and drop blank lines. This never touches
  // anything semantically required, it only shrinks the payload so the
  // pairing QR code stays small enough to scan reliably.
  function compactSdp(sdp) {
    return sdp
      .split(/\r?\n/)
      .filter(Boolean)
      .map(function (line) {
        if (line.indexOf('a=candidate:') === 0) {
          var parts = line.split(' ');
          var typIdx = parts.indexOf('typ');
          if (typIdx >= 0) return parts.slice(0, typIdx + 2).join(' ');
        }
        return line;
      })
      .join('\r\n') + '\r\n';
  }

  function waitIceComplete(pc) {
    if (pc.iceGatheringState === 'complete') return Promise.resolve();
    return new Promise(function (resolve) {
      function check() {
        if (pc.iceGatheringState === 'complete') {
          pc.removeEventListener('icegatheringstatechange', check);
          resolve();
        }
      }
      pc.addEventListener('icegatheringstatechange', check);
      // Safety timeout: some networks never reach "complete" cleanly.
      setTimeout(resolve, 4000);
    });
  }

  // ------------------------------------------------------------------ QR

  function makeQrDataUrl(text) {
    var lastErr = null;
    for (var ver = 3; ver <= 40; ver += 1) {
      try {
        var qr = window.qrcode(ver, 'L');
        qr.addData(text);
        qr.make();
        var modules = ver * 4 + 17;
        var cell = Math.max(2, Math.floor(360 / modules));
        return qr.createDataURL(cell, 4);
      } catch (e) {
        lastErr = e;
      }
    }
    throw lastErr || new Error('QR-Inhalt zu groß');
  }

  function decodeQrFromImageData(imageData) {
    if (!window.jsQR) return null;
    return window.jsQR(imageData.data, imageData.width, imageData.height, { inversionAttempts: 'dontInvert' });
  }

  // ------------------------------------------------------------ transfers

  function extOf(name) {
    var m = /\.([a-z0-9]+)$/i.exec(name || '');
    return m ? m[1].toUpperCase() : '';
  }
  function fmtBytes(n) {
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return Math.round(n / 1024) + ' KB';
    return (n / (1024 * 1024)).toFixed(1) + ' MB';
  }

  // ---------------------------------------------------------------- Net

  function TransformerNet() {
    this._ev = emitter();
    this.peers = loadPeers(); // [{id, name, type, keyB64}]
    this._links = {}; // id -> {pc, dc, key, status}
    this._pendingOffer = null; // {pairingId, pc, dc, key, keyB64, reconnectId}
    this._incoming = {}; // transferId -> {meta, chunks, received} (present only once accepted)
    this._fileOutbox = {}; // peerId -> [{file, transferId}] — in-memory only, see sendFile()
    this._cancelled = {}; // transferId -> true
    this._outbox = loadOutbox(); // id -> [{msgId, obj}]
    this.deviceId = this._loadOrCreateDeviceId();
  }

  TransformerNet.prototype.on = function (ev, cb) { return this._ev.on(ev, cb); };

  TransformerNet.prototype._loadOrCreateDeviceId = function () {
    var key = 'transformer.selfid.v1';
    var id = null;
    try { id = localStorage.getItem(key); } catch (e) {}
    if (!id) {
      id = randomId();
      try { localStorage.setItem(key, id); } catch (e) {}
    }
    return id;
  };

  TransformerNet.prototype.getPeer = function (id) {
    return this.peers.filter(function (p) { return p.id === id; })[0] || null;
  };
  TransformerNet.prototype.status = function (id) {
    var link = this._links[id];
    return link ? link.status : 'offline';
  };

  TransformerNet.prototype._savePeer = function (id, name, keyB64) {
    var existing = this.getPeer(id);
    if (existing) {
      existing.name = name;
      if (keyB64) existing.keyB64 = keyB64;
    } else {
      this.peers.push({ id: id, name: name, keyB64: keyB64 });
    }
    savePeers(this.peers);
  };

  TransformerNet.prototype.renamePeer = function (id, name) {
    var p = this.getPeer(id);
    if (p) { p.name = name; savePeers(this.peers); }
  };

  TransformerNet.prototype.forgetPeer = function (id) {
    this.peers = this.peers.filter(function (p) { return p.id !== id; });
    savePeers(this.peers);
    delete this._outbox[id];
    saveOutbox(this._outbox);
    var link = this._links[id];
    if (link) {
      try { link.dc && link.dc.close(); } catch (e) {}
      try { link.pc && link.pc.close(); } catch (e) {}
      delete this._links[id];
    }
  };

  // -------------------------------------------------------- QR: offering

  TransformerNet.prototype.createOfferPayload = function (myName, reuseId) {
    var self = this;
    var existing = reuseId ? self.getPeer(reuseId) : null;
    var pairingId = existing ? existing.id : randomId();
    var pc = new RTCPeerConnection({ iceServers: [] });
    var dc = pc.createDataChannel('transformer', { ordered: true });

    return (existing ? importKeyB64(existing.keyB64) : generateKey()).then(function (key) {
      return pc.createOffer()
        .then(function (offer) { return pc.setLocalDescription(offer); })
        .then(function () { return waitIceComplete(pc); })
        .then(function () { return exportKeyB64(key); })
        .then(function (keyB64) {
          self._pendingOffer = { pairingId: pairingId, pc: pc, dc: dc, key: key, keyB64: keyB64, isReconnect: !!existing };
          var payload = { v: 1, t: 'offer', id: pairingId, name: myName, sdp: compactSdp(pc.localDescription.sdp) };
          if (!existing) payload.key = keyB64;
          return JSON.stringify(payload);
        });
    });
  };

  // ------------------------------------------------------- QR: scanning

  TransformerNet.prototype.handleScannedPayload = function (text, myName) {
    var self = this;
    var payload;
    try { payload = JSON.parse(text); } catch (e) { return Promise.reject(new Error('Kein gültiger Transformer-Code')); }
    if (!payload || (payload.t !== 'offer' && payload.t !== 'answer')) {
      return Promise.reject(new Error('Kein gültiger Transformer-Code'));
    }

    if (payload.t === 'offer') {
      var keyPromise = payload.key ? importKeyB64(payload.key) : (function () {
        var existing = self.getPeer(payload.id);
        if (!existing) return Promise.reject(new Error('Unbekanntes Gerät — bitte neu koppeln'));
        return importKeyB64(existing.keyB64);
      })();
      return keyPromise.then(function (key) {
        var pc = new RTCPeerConnection({ iceServers: [] });
        var boundChannel = new Promise(function (resolve) {
          pc.ondatachannel = function (e) { resolve(e.channel); };
        });
        return pc.setRemoteDescription({ type: 'offer', sdp: payload.sdp }).then(function () {
          return pc.createAnswer();
        }).then(function (answer) {
          return pc.setLocalDescription(answer);
        }).then(function () {
          return waitIceComplete(pc);
        }).then(function () {
          return exportKeyB64(key);
        }).then(function (keyB64) {
          self._savePeer(payload.id, payload.name, keyB64);
          boundChannel.then(function (channel) {
            self._bindChannel(payload.id, key, pc, channel);
          });
          var answerPayload = { v: 1, t: 'answer', id: payload.id, name: myName, sdp: compactSdp(pc.localDescription.sdp) };
          return { role: 'answerer', qrPayload: JSON.stringify(answerPayload), peer: { id: payload.id, name: payload.name } };
        });
      });
    }

    // payload.t === 'answer'
    var pending = self._pendingOffer;
    if (!pending || pending.pairingId !== payload.id) {
      return Promise.reject(new Error('Dieser Code passt zu keiner offenen Anfrage'));
    }
    return pending.pc.setRemoteDescription({ type: 'answer', sdp: payload.sdp }).then(function () {
      self._savePeer(pending.pairingId, payload.name, pending.keyB64);
      self._bindChannel(pending.pairingId, pending.key, pending.pc, pending.dc);
      self._pendingOffer = null;
      return { role: 'offerer-complete', peer: { id: pending.pairingId, name: payload.name } };
    });
  };

  TransformerNet.prototype.cancelPending = function () {
    if (this._pendingOffer) {
      try { this._pendingOffer.pc.close(); } catch (e) {}
      this._pendingOffer = null;
    }
  };

  // --------------------------------------------------------- data channel

  TransformerNet.prototype._bindChannel = function (peerId, key, pc, dc) {
    var self = this;
    var link = self._links[peerId] = { pc: pc, dc: dc, key: key, status: dc.readyState === 'open' ? 'open' : 'connecting' };
    self._ev.emit('status', peerId, link.status);

    dc.onopen = function () {
      link.status = 'open';
      self._ev.emit('status', peerId, 'open');
      self._flushOutbox(peerId);
      self._flushFileOutbox(peerId);
    };
    dc.onclose = function () {
      link.status = 'offline';
      self._ev.emit('status', peerId, 'offline');
    };
    pc.onconnectionstatechange = function () {
      if (pc.connectionState === 'failed' || pc.connectionState === 'closed') {
        link.status = 'offline';
        self._ev.emit('status', peerId, 'offline');
      }
    };
    dc.onmessage = function (e) { self._onRawMessage(peerId, key, e.data); };
  };

  TransformerNet.prototype._onRawMessage = function (peerId, key, raw) {
    var self = this;
    var frame;
    try { frame = JSON.parse(raw); } catch (e) { return; }
    decryptJson(key, frame.envelope).then(function (body) {
      if (frame.type === 'msg') {
        self._ev.emit('message', peerId, body);
      } else if (frame.type === 'file-meta') {
        self._ev.emit('incoming-request', peerId, body);
      } else if (frame.type === 'file-chunk') {
        var t = self._incoming[body.transferId];
        if (!t) return; // not yet accepted (or rejected) — drop until the app decides
        if (!t.chunks[body.index]) { t.chunks[body.index] = body.chunkB64; t.received += 1; }
        var pct = Math.round((t.received / t.meta.totalChunks) * 100);
        self._ev.emit('progress', peerId, body.transferId, {
          direction: 'receive', name: t.meta.name, size: t.meta.size, ext: extOf(t.meta.name),
          status: 'active', receivedPct: pct,
        });
        if (t.received === t.meta.totalChunks) {
          var binStr = t.chunks.map(function (c) { return atob(c); }).join('');
          var bytes = new Uint8Array(binStr.length);
          for (var i = 0; i < binStr.length; i++) bytes[i] = binStr.charCodeAt(i);
          var blob = new Blob([bytes], { type: t.meta.mime || 'application/octet-stream' });
          var url = URL.createObjectURL(blob);
          delete self._incoming[body.transferId];
          self._ev.emit('progress', peerId, body.transferId, {
            direction: 'receive', name: t.meta.name, size: t.meta.size, ext: extOf(t.meta.name),
            status: 'done', receivedPct: 100, url: url,
          });
          self._sendFrame(peerId, 'ack', { transferId: body.transferId });
        }
      } else if (frame.type === 'ack') {
        self._ev.emit('progress', peerId, body.transferId, { direction: 'send', status: 'done', receivedPct: 100 });
      }
    }).catch(function (e) { console.error('net.js decrypt failed', e); });
  };

  TransformerNet.prototype.acceptIncomingTransfer = function (peerId, meta) {
    this._incoming[meta.transferId] = { meta: meta, chunks: new Array(meta.totalChunks), received: 0 };
    this._ev.emit('progress', peerId, meta.transferId, {
      direction: 'receive', name: meta.name, size: meta.size, ext: extOf(meta.name), status: 'active', receivedPct: 0,
    });
  };
  TransformerNet.prototype.rejectIncomingTransfer = function (peerId, meta) {
    this._ev.emit('progress', peerId, meta.transferId, {
      direction: 'receive', name: meta.name, size: meta.size, ext: extOf(meta.name), status: 'failed', reason: 'Abgelehnt',
    });
  };

  TransformerNet.prototype.cancelTransfer = function (peerId, transferId) {
    var q = this._fileOutbox[peerId];
    if (q) {
      var idx = q.findIndex(function (item) { return item.transferId === transferId; });
      if (idx >= 0) {
        var item = q.splice(idx, 1)[0];
        this._ev.emit('progress', peerId, transferId, {
          direction: 'send', name: item.file.name, size: item.file.size, ext: extOf(item.file.name), status: 'failed', reason: 'Vom Nutzer abgebrochen',
        });
        return;
      }
    }
    this._cancelled[transferId] = true;
  };

  TransformerNet.prototype._sendFrame = function (peerId, type, body) {
    var link = this._links[peerId];
    if (!link || link.dc.readyState !== 'open') return Promise.reject(new Error('offline'));
    return encryptJson(link.key, body).then(function (envelope) {
      link.dc.send(JSON.stringify({ type: type, envelope: envelope }));
    });
  };

  // -------------------------------------------------------------- outbox

  TransformerNet.prototype._queue = function (peerId, item) {
    var q = this._outbox[peerId] = this._outbox[peerId] || [];
    q.push(item);
    saveOutbox(this._outbox);
  };

  TransformerNet.prototype._flushOutbox = function (peerId) {
    var self = this;
    var q = self._outbox[peerId];
    if (!q || !q.length) return;
    self._outbox[peerId] = [];
    saveOutbox(self._outbox);
    q.forEach(function (item) {
      self._sendFrame(peerId, 'msg', item.body).then(function () {
        self._ev.emit('delivered', peerId, item.msgId);
      }).catch(function () {
        self._queue(peerId, item);
      });
    });
  };

  TransformerNet.prototype.queuedCount = function (peerId) {
    return (this._outbox[peerId] || []).length;
  };

  // ------------------------------------------------------------- sending

  TransformerNet.prototype.sendMessage = function (peerId, body) {
    var msgId = body.msgId || randomId();
    body.msgId = msgId;
    var link = this._links[peerId];
    if (link && link.dc.readyState === 'open') {
      return this._sendFrame(peerId, 'msg', body).then(function () { return { queued: false, msgId: msgId }; });
    }
    this._queue(peerId, { msgId: msgId, body: body });
    return Promise.resolve({ queued: true, msgId: msgId });
  };

  // Files queued while the peer is offline live only in memory (a real
  // File object can't cheaply round-trip through localStorage), so they are
  // resent automatically the moment that peer's data channel opens again —
  // but only for as long as this tab stays open. Documented limitation: if
  // the tab is closed before delivery, the file has to be sent again.
  TransformerNet.prototype.sendFile = function (peerId, file) {
    var self = this;
    var link = self._links[peerId];
    var transferId = randomId();

    if (!link || link.dc.readyState !== 'open') {
      (self._fileOutbox[peerId] = self._fileOutbox[peerId] || []).push({ file: file, transferId: transferId });
      self._ev.emit('progress', peerId, transferId, {
        direction: 'send', name: file.name, size: file.size, ext: extOf(file.name), status: 'waiting', receivedPct: 0,
      });
      return Promise.resolve({ queued: true, transferId: transferId });
    }
    return self._doSendFile(peerId, file, transferId).then(function () {
      return { queued: false, transferId: transferId };
    });
  };

  TransformerNet.prototype._flushFileOutbox = function (peerId) {
    var self = this;
    var q = self._fileOutbox[peerId];
    if (!q || !q.length) return;
    self._fileOutbox[peerId] = [];
    q.forEach(function (item) {
      self._doSendFile(peerId, item.file, item.transferId).catch(function () {});
    });
  };

  TransformerNet.prototype._doSendFile = function (peerId, file, transferId) {
    var self = this;
    var link = self._links[peerId];
    var totalChunks = Math.max(1, Math.ceil(file.size / FILE_CHUNK_BYTES));
    var meta = { transferId: transferId, name: file.name, size: file.size, mime: file.type, totalChunks: totalChunks, time: Date.now() };

    self._ev.emit('progress', peerId, transferId, {
      direction: 'send', name: file.name, size: file.size, ext: extOf(file.name), status: 'active', receivedPct: 0,
    });

    function readChunk(index) {
      var start = index * FILE_CHUNK_BYTES;
      var end = Math.min(file.size, start + FILE_CHUNK_BYTES);
      return file.slice(start, end).arrayBuffer().then(function (buf) {
        return b64FromBytes(new Uint8Array(buf));
      });
    }
    function waitForBuffer(dc) {
      if (dc.bufferedAmount < BUFFERED_AMOUNT_LOW) return Promise.resolve();
      return new Promise(function (resolve) {
        dc.bufferedAmountLowThreshold = BUFFERED_AMOUNT_LOW;
        dc.addEventListener('bufferedamountlow', function onLow() {
          dc.removeEventListener('bufferedamountlow', onLow);
          resolve();
        });
      });
    }

    return self._sendFrame(peerId, 'file-meta', meta).then(function () {
      var chain = Promise.resolve();
      var _loop = function (i) {
        chain = chain.then(function () { return waitForBuffer(link.dc); })
          .then(function () {
            if (self._cancelled[transferId]) throw new Error('Abgebrochen');
            return readChunk(i);
          })
          .then(function (chunkB64) { return self._sendFrame(peerId, 'file-chunk', { transferId: transferId, index: i, chunkB64: chunkB64 }); })
          .then(function () {
            var pct = Math.round(((i + 1) / totalChunks) * 100);
            self._ev.emit('progress', peerId, transferId, {
              direction: 'send', name: file.name, size: file.size, ext: extOf(file.name), status: 'active', receivedPct: pct,
            });
          });
      };
      for (var i = 0; i < totalChunks; i++) _loop(i);
      return chain;
    }).then(function () {
      delete self._cancelled[transferId];
    }).catch(function (err) {
      delete self._cancelled[transferId];
      self._ev.emit('progress', peerId, transferId, {
        direction: 'send', name: file.name, size: file.size, ext: extOf(file.name), status: 'failed', receivedPct: 0, reason: String(err && err.message || err),
      });
      throw err;
    });
  };

  // ---------------------------------------------------------------- misc

  TransformerNet.prototype.fmtBytes = fmtBytes;
  TransformerNet.prototype.extOf = extOf;
  TransformerNet.prototype.makeQrDataUrl = makeQrDataUrl;
  TransformerNet.prototype.decodeQrFromImageData = decodeQrFromImageData;

  // Guard against net.js being executed more than once on the same page
  // (the design-canvas runtime can re-insert <helmet> scripts) — a second
  // run must never replace the live instance and orphan its subscribers.
  if (!window.TransformerNet) window.TransformerNet = new TransformerNet();
})();
