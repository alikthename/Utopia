// not sure about addMe business...
// registrar uses loopback option immediately so address changes once registered; does this matter
// might be nice if addition to the AddrBook only took place once registered

// uses dependancy to update interested parties
// Peer spoofs a NetAddr for sending purposes

Peer {
	var <name, <addr, <online;

	*new {|name, addr, online = true|
		^super.newCopyArgs(name.asSymbol, addr, online);
	}

	*newFrom { |item|
		if(item.isKindOf(this)) { ^item };
		item = item ?? { this.defaultName };
		^this.new(item, NetAddr.localAddr)
	}

	*defaultName {
		^"whoami".unixCmdGetStdOut.replace("\n", "");
	}

	online_ {|bool| if(bool != online, { online = bool; this.changed(\online) }) }

	== {|other|
		^this.name == other.name && {this.addr.matches(other.addr)};
	}

	hash {
		^this.instVarHash(#[\name, \addr, \online])
	}

	// post pretty
	printOn { |stream|
		stream << this.class.name << "(" <<* [name, addr, online] << ")"
	}

	sendRaw { arg rawArray;
		addr.sendRaw(rawArray);
	}

	sendMsg { arg ... args;
		addr.sendMsg(*args);
	}

	sendBundle { arg time ... args;
		addr.sendBundle(time, *args);
	}

	sendClumpedBundles { arg time ... args;
		addr.sendClumpedBundles(time, *args);
	}
}

AddrBook {
	var dict, <me;

	*new { ^super.new.init }

	init { dict = IdentityDictionary.new; }

	// maybe don't need this anymore
	send {|name ...msg| dict[name].addr.sendMsg(*msg) }

	sendAll {|...msg| dict.do({|peer| peer.addr.sendMsg(*msg); }); }

	sendAllBundle {|time ...msg| dict.do({|peer| peer.addr.sendBundle(time, *msg); }); }

	sendExcluding {|name ...msg| dict.reject({|peer, peerName| peerName == name }).do({|peer| peer.addr.sendMsg(*msg); });}

	add {|peer|
		peer = peer.as(Peer);
		dict[peer.name] = peer;
		peer.addDependant(this);
		this.changed(\add, peer)
	}

	addMe {|mePeer|
		mePeer = mePeer.as(Peer);
		this.add(mePeer);
		me = mePeer;
	}

	at {|name| ^dict[name] }

	remove {|peer| dict[peer.name] = nil; peer.removeDependant(this); this.changed(\remove, peer) }

	removeAt {|name| this.remove(dict[name]) }

	update {|changed, what| this.changed(what, changed) }

	names { ^dict.keys }

	addrs { ^dict.values.collect({|peer| peer.addr }) }

	// methods to generate PeerGroups

	onlinePeers { ^PeerGroup(\online, dict.reject({|peer| peer.online.not }).values) }

	peers { ^PeerGroup(\all, dict.values) }

	others { ^PeerGroup(\others, dict.values.reject({|peer| peer.name == me.name })) }

	excluding {|name|
		^PeerGroup(("excluding-" ++ name).asSymbol, dict.values.reject({|peer| peer.name == name }))
	}

	asAddrBook { }
}

// a Dict like class that provides a way to register Servers automatically between a number of Peers
// the addrBook contains NetAddrs for Peers (sclang!) that will add servers
// the dataspace is a shared dictionary of ips and ports
// for 1 server per client use addMyServer, but other arrangements are possible
// Todo: allow for sharing of ServerOptions
ServerRegistry {
	var addrBook, options, oscPath, oscDataSpace, serverDict, myServer;
	var dependancyFunc;

	// maybe should have an encryptor as well
	*new {|addrBook, options, oscPath = '/serverRegistry'|
		^super.newCopyArgs(addrBook, options, oscPath).addDataSpace;
	}

	addMyServer {|server|
		// avoid changed call for local case
		oscDataSpace.removeDependant(dependancyFunc);
		myServer = server ?? {Server.default};
		this[addrBook.me.name] = myServer;
		oscDataSpace.addDependant(dependancyFunc);
	}

	put {|name, server|
		serverDict[name] = server;
		oscDataSpace[name] = server !? {server.addr};
	}

	addDataSpace {
		serverDict = IdentityDictionary.new;
		oscDataSpace = OSCObjectSpace(addrBook, false, oscPath); // a dataspace of server ports
		oscDataSpace.addDependant(dependancyFunc = {|changed, what, name, addr|
			if(what == \val, {
				// avoid loopback
				if(addr.ip == "127.0.0.1", {addr = NetAddr(addrBook[name].addr.ip, addr.port)});

				// could also have peers send the options for their servers
				serverDict[name] = Server(name, addr, options);
			});
		});
	}

	at {|key| ^serverDict[key] }

	keys { ^serverDict.keys }

	values { ^serverDict.values }

	asAddrBook { ^addrBook }
}

// PeerGroup is an Array that spoofs a NetAddr

PeerGroup[slot] : Array {
	var <>name;

	*new {|name, peers| ^super.new.name_(name).addAll(peers) }

	sendRaw { arg rawArray;
		this.do({|peer| peer.addr.sendRaw(rawArray)});
	}

	sendMsg { arg ... args;
		this.do({|peer| peer.addr.sendMsg(*args)});
	}

	sendBundle { arg time ... args;
		this.do({|peer| peer.addr.sendBundle(time, *args)});
	}

	sendClumpedBundles { arg time ... args;
		this.do({|peer| peer.addr.sendClumpedBundles(time, *args)});
	}

	printOn { arg stream;
		if (stream.atLimit, { ^this });
		stream << "PeerGroup[ " ;
		this.printItemsOn(stream);
		stream << " ]" ;
	}
}

// Syncs groups between Peers
// addrDict could be any dictionary like object that understands the asAddrBook method, so probably an AddrBook or a ServerRegistry
// it translates the groups keys into a PeerGroup for sending
PeerGroupManager {
	var <addrDict, dataSpace;

	*new {|addrDict, oscPath = \groupsDataSpace|
		^super.newCopyArgs(addrDict).init(oscPath);
	}

	init { |oscPath| dataSpace = OSCObjectSpace(addrDict, false, oscPath);  dataSpace.addDependant(this); }

	add { |groupname, names| dataSpace.put(groupname, names);  }

	remove { |groupname| dataSpace.put(groupname, nil); }

	at {|groupname| ^PeerGroup(groupname, this.resolve(groupname)); }

	keysAt {|groupname| ^dataSpace[groupname] }

	resolve {|groupname| ^dataSpace[groupname].collect({|name| addrDict[name]}) }

	update { |… args| this.changed(args) } // register dependants to observe changes in groups
}

// who's there?
Hail {
	var <addrBook, period, oscPath, authenticator, broadcastAddr, me, inOSCFunc, outOSCFunc, lastResponses;

	*new { |addrBook, period = 1.0, me, authenticator, oscPath = '/hail', broadcastAddr|
		addrBook = addrBook ?? { AddrBook.new };
		^super.newCopyArgs(addrBook, period, oscPath, authenticator, broadcastAddr).init(me);
	}

	// not totally sure about this me business...
	init {|argMe|
		if(argMe.notNil, {addrBook.addMe(argMe)}, { if(addrBook.me.isNil, {addrBook.addMe }) });
		me = addrBook.me;
		lastResponses = IdentityDictionary.new;
		authenticator = authenticator ?? { NonAuthenticator };
		this.makeOSCFuncs;
		this.hailingSignal;
	}

	makeOSCFuncs {
		var replyPath;
		replyPath = (oscPath ++ "-reply").asSymbol;
		// add both on receiving a hail, and on receiving a reply
		// this makes discovery more robust when there're multiple interfaces
		inOSCFunc = OSCFunc({|msg, time, addr|
			var name;
			name = msg[1];
			this.updateForAddr(name, addr, time);
		}, replyPath, recvPort: addrBook.me.addr.port).fix;

		outOSCFunc = OSCFunc({|msg, time, addr|
			var name;
			name = msg[1];
			this.updateForAddr(name, addr, time);
			addr.sendMsg(replyPath, me.name);
		}, oscPath, recvPort: addrBook.me.addr.port).fix;
	}

	updateForAddr {|name, addr, time|
		var peer;
		if(lastResponses[name].isNil, {
			peer = Peer(name, addr);
			authenticator.authenticate(peer, {
				addrBook.add(peer);
				addrBook[name].online = true;
				lastResponses[name] = time;
			});
		}, {
			addrBook[name].online = true;
			lastResponses[name] = time;
		});
	}

	free { inOSCFunc.free; outOSCFunc.free; }

	hailingSignal {
		NetAddr.broadcastFlag = true;
		broadcastAddr = broadcastAddr ?? {NMLNetAddrMP("255.255.255.255", 57120 + (0..7))};
		SystemClock.sched(0, {
			broadcastAddr.sendMsg(oscPath, me.name);
			if(period.notNil, { this.checkOnline; });
			period;
		});
	}

	// everybody still there?
	checkOnline {
		var now;
		now = Main.elapsedTime;
		lastResponses.keysValuesDo({|name, lastHeardFrom|
			if((now - lastHeardFrom) > (period * 2), { addrBook[name].online = false });
		});
	}

}

// Centralised
Registrar {
	var <addrBook, period, authenticator, oscPath, lastResponses, pingRegistrarOSCFunc, registerOSCFunc, unRegisterOSCFunc, pingReplyOSCFunc;

	*new { |addrBook, period = 1.0, authenticator, oscPath = '/register'|
		addrBook = addrBook ?? { AddrBook.new };
		^super.newCopyArgs(addrBook, period, authenticator, oscPath).init;
	}

	init {
		lastResponses = IdentityDictionary.new;
		authenticator = authenticator ?? { NonAuthenticator };
		this.makeOSCFuncs;
		period.notNil.if({ this.ping; });
	}

	makePeer {|addr, name|
		^Peer(name, addr);
	}

	makeOSCFuncs {
		// people are looking for me
		pingRegistrarOSCFunc = OSCFunc({|msg, time, addr|
			addr.sendMsg(oscPath ++ "-pingRegistrarReply"); // I'm here!
		}, oscPath ++ "-pingRegistrar").fix;

		registerOSCFunc = OSCFunc({|msg, time, addr|
			var peer;
			peer = this.makePeer(addr, msg[1]);
			authenticator.authenticate(peer, {
				// tell everyone about the new arrival
				addrBook.sendAll(oscPath ++ "-add", peer.name, addr.ip, addr.port);
				// tell the new arrival about everyone
				addrBook.peers.do({|peer|
					addr.sendMsg(oscPath ++ "-add", peer.name, peer.addr.ip, peer.addr.port);
				});
				addrBook.add(peer);
			});
		}, oscPath).fix;

		unRegisterOSCFunc = OSCFunc({|msg, time, addr|
			var name;
			name = msg[1];
			addrBook.removeAt(name);
			lastResponses[name] = nil;
			addrBook.sendExcluding(name, oscPath ++ "-remove", name);
		}, oscPath ++ "-unregister").fix;

		// make sure everyone is still online
		pingReplyOSCFunc = OSCFunc({|msg, time, addr|
			var name, peer;
			name = msg[1];
			peer = addrBook[name];
			peer.notNil.if({
				peer.online_(true);
				lastResponses[name] = time;
				addrBook.sendAll(oscPath ++ "-online", name, true.binaryValue);
			});
		}, oscPath ++ "-pingReply").fix;
	}

	free { pingRegistrarOSCFunc.free; registerOSCFunc.free; unRegisterOSCFunc.free; pingReplyOSCFunc.free }

	ping {
		SystemClock.sched(0, {
			addrBook.sendAll(oscPath ++ "-ping");
			this.checkOnline;
			period;
		});
	}

	// everybody still there?
	checkOnline {
		var now;
		now = Main.elapsedTime;
		lastResponses.keysValuesDo({|name, lastHeardFrom|
			if((now - lastHeardFrom) > (period * 2), {
				addrBook[name].online = false;
				addrBook.sendAll(oscPath ++ "-online", name, false.binaryValue);
			});
		});
	}

}

Registrant {
	var <addrBook, registrarAddr, authenticator, oscPath, broadcastAddr, me, addOSCFunc, removeOSCFunc, onlineOSCFunc, pingOSCFunc, pinging;

	// we pass an authenticator here but maybe it's unnecessary. It's simply there to respond, not challenge in this case.
	*new { |addrBook, me, registrarAddr, authenticator, oscPath = '/register', broadcastAddr|
		addrBook = addrBook ?? { AddrBook.new };
		^super.newCopyArgs(addrBook, registrarAddr, authenticator, oscPath, broadcastAddr).init(me);
	}

	// not totally sure about this me business...
	init {|argMe|
		if(argMe.notNil, {addrBook.addMe(argMe)}, { if(addrBook.me.isNil, {addrBook.addMe }) });
		me = addrBook.me;
		this.addOSCFuncs;
		if(registrarAddr.isNil, { this.pingRegistrar }, { this.register });
	}

	makePeer {|name, hostname, port|
		^Peer(name, NetAddr(hostname.asString, port));
	}

	addOSCFuncs {
		addOSCFunc = OSCFunc({|msg, time, addr|
			var peer;
			peer = this.makePeer(*msg[1..]);
			addrBook.add(peer);
		}, oscPath ++ "-add", registrarAddr, recvPort: addrBook.me.addr.port).fix;

		removeOSCFunc = OSCFunc({|msg, time, addr|
			var name;
			name = msg[1];
			addrBook.removeAt(name);
		}, oscPath ++ "-remove", registrarAddr, recvPort: addrBook.me.addr.port).fix;

		onlineOSCFunc = OSCFunc({|msg, time, addr|
			var name, peer;
			name = msg[1];
			peer = addrBook[name];
			peer.notNil.if({ peer.online_(msg[2].booleanValue) });
		}, oscPath ++ "-online", registrarAddr, recvPort: addrBook.me.addr.port).fix;

		pingOSCFunc = OSCFunc({|msg, time, addr|
			registrarAddr.sendMsg(oscPath ++ "-pingReply", me.name);
		}, oscPath ++ "-ping", registrarAddr, recvPort: addrBook.me.addr.port).fix;
	}

	free { pinging = false; this.unregister; addOSCFunc.free; removeOSCFunc.free; onlineOSCFunc.free; pingOSCFunc.free; }

	register {
		registrarAddr.sendMsg(oscPath, me.name);
	}

	unregister {
		registrarAddr.sendMsg(oscPath ++ "-unregister", me.name);
	}

	// automatically search for registrar...
	pingRegistrar {
		var registrarPingOSCFunc;
		pinging = true;
		NetAddr.broadcastFlag = true;
		broadcastAddr = broadcastAddr ?? { NMLNetAddrMP("255.255.255.255", 57120 + (0..7))};
		registrarPingOSCFunc = OSCFunc({|msg, time, addr|
			pinging = false;
			registrarAddr = addr;
			this.register;
		}, oscPath ++ "-pingRegistrarReply", recvPort: addrBook.me.addr.port).oneShot;

		{
			while( { pinging }, {
				broadcastAddr.sendMsg(oscPath ++ "-pingRegistrar");
				1.wait;
			});
		}.fork;
	}

}

// implements a NetAddr that can have multiple ports...
// this is the same as in Republic, but we duplicate here for now in order to avoid the dependancy
NMLNetAddrMP : NetAddr {

	var <>ports;

	*new { arg hostname, ports;
		ports = ports.asArray;
		^super.new(hostname, ports.first).ports_(ports)
	}

	sendRaw{ arg rawArray;
		ports.do{ |it|
			this.port_( it );
			^super.sendRaw( rawArray );
		}
	}

	sendMsg { arg ... args;
		ports.do{ |it|
			this.port_( it );
			super.sendMsg( *args );
		}
	}

	sendBundle { arg time ... args;
		ports.do{ |it|
			this.port_( it );
			super.sendBundle( *([time]++args) );
		}
	}
}

