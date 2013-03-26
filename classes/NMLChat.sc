// simple chat class
// dependants can do something with it
// use different oscPath for different 'channels' eg Shout
Chatter {
	var addrBook, <>post, oscPath, oscFunc;

	*new {|addrBook, post = true, oscPath = '/chat'|
		^super.newCopyArgs(addrBook, post, oscPath).makeOSCFunc;
	}

	makeOSCFunc {
		oscFunc = OSCFunc({|msg, time, addr|
			var name, chat;
			name = msg[1];
			chat = msg[2];
			this.changed(\chat, name, chat);
			if(post, {
				(name.asString ++ ": " ++ chat).postln;
			});
		}, oscPath);
	}

	free { oscFunc.free; }

	send {|chat|
		addrBook.sendAll(oscPath, addrBook.me.name, chat);
	}

	sendPrivate {|name, chat|
		addrBook.send(name, oscPath, addrBook.me.name, chat);
	}
}