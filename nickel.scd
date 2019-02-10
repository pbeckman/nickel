(

var chan = 0;
var mic_ins = [1, 2];
var num_chan = size(mic_ins);
var mixer_in = 0;
var main_out = 0;
var speaker_out = 2;
var mixer_out = 6;
var audio_bus = Bus.audio(s, num_chan + 1);
var allvol_bus = Bus.control(s, 3);
var fbcontrol_bus = Bus.control(s, 2 * num_chan);
var trigger_bus = Bus.control(s, num_chan * num_chan);
var bp_bus = Bus.control(s, num_chan);
var sens_bus = Bus.control(s, num_chan);
var fx_group = Group.tail(s);
var vol_group = Group.tail(s);
var midi_out = MIDIOut.newByName("TouchOSC Bridge", "TouchOSC Bridge");

MIDIIn.connectAll;

// INITIALIZATION

// reset GUI to zero
127.do({arg i;
	midi_out.control(0, i, 0);
});

// initialize volumes to zero
3.do({arg i;
	allvol_bus.setAt(i, 0);
});

// initialize feedback to zero and turn it off
(2 * num_chan).do({arg i;
	fbcontrol_bus.setAt(i, 0);
});

// initialize all triggering off
(num_chan * num_chan).do({arg i;
	trigger_bus.setAt(i, 0);
});

// initialize bandpass busses to avoid DEAFENING artifacts
2.do({arg i;
	bp_bus.setAt(i, 400);
});

// initialize sensitivity bus
2.do({arg i;
	sens_bus.setAt(i, 0.01);
});

// PROCESSING UNITS

SynthDef.new(\Feedback, {arg ch, in, out, slope_bus, toggle_bus, bp_bus, sens_bus;
	var signal = SoundIn.ar(in);
	var onset = Coyote.kr(
		signal,
		fastMul:LinLin.kr(In.kr(sens_bus), 0, 127, 0.01, 0.9)
	);
	var slope = Select.kr(In.kr(toggle_bus) > 0, [
		2.0,
		LinLin.kr(In.kr(slope_bus), 0, 127, 0.7, 0.2)
	]);

	// send trigger when onset detected
	SendTrig.kr(onset, 0, ch);

	signal = BBandPass.ar(signal,
		freq:In.kr(bp_bus).poll,
		bw:2.0
	);

	signal = Compander.ar(signal,
		control:signal,
		thresh:0.5,
		slopeBelow:slope,
		slopeAbove:0.2,
		clampTime:0.01,
		relaxTime:0.01
	);
	signal = Limiter.ar(
		signal,
		1.0,
		0.01
	);

	Out.ar(out, signal);
}).load(s);

SynthDef.new(\Volume, {arg in, num_in=1, out, num_out=0, vol_bus;
	var signal = In.ar(in, 2);
	var amp = LinExp.kr(In.kr(vol_bus), 0, 127, 0.0001, 1.0);

	signal = signal * amp;
	signal = Limiter.ar(
		signal,
		1.0,
		0.01
	);

	Out.ar(out, Mix(signal))
}).load(s);

// IMPLEMENT CONTROLS

// write volume sliders vol_bus
MIDIFunc.cc({arg val, cc_num;
	allvol_bus.setAt(cc_num, val);
}, [0,1,2], chan);

// write feedback controls to fbcontrol_bus
MIDIFunc.cc({arg val, cc_num;
	fbcontrol_bus.setAt(cc_num - 10, val);
}, (10..(10 + 2*num_chan)), chan);

// write trigger toggles to trigger_bus
MIDIFunc.cc({arg val, cc_num;
	trigger_bus.setAt(cc_num - 20, val);
}, (20..(20 + num_chan*num_chan)), chan);

// write bandpass sliders bp_bus
MIDIFunc.cc({arg val, cc_num;
	bp_bus.setAt(cc_num - 70, val.linexp(0, 127, 20, 20000));
}, (70..(70 + num_chan)), chan);

// write sensitivity sliders sens_bus
MIDIFunc.cc({arg val, cc_num;
	sens_bus.setAt(cc_num - 50, val);
}, (50..(50 + num_chan)), chan);

// randomize bandpass on button push
num_chan.do({arg i;
	MIDIFunc.cc({
		var val = exprand(20, 20000);
		bp_bus.setAt(i, val);
		midi_out.control(0, 70 + i, val.explin(20, 20000, 0, 127));
	}, 40 + i, chan);
});

// randomize bandpass for toggled channels on trigger
OSCFunc({arg msg;
	var ch = msg[3];
	var val = exprand(20, 20000);
	var triggers = trigger_bus.getn(
		num_chan * num_chan, {arg triggers;
			triggers = triggers[((ch * num_chan)..(((ch+1) * num_chan) - 1))];
			// triggers.postln;
			num_chan.do({arg i;
				if(triggers[i] == 127, {
					bp_bus.setAt(i, val);
					midi_out.control(0, 70 + i, val.explin(20, 20000, 0, 127));
				});
			});
	});
	midi_out.control(0, 60 + ch, rrand(0,127));
},'/tr', s.addr);

// CREATE PROCESSING UNITS

// make feedback units
num_chan.do({arg i;
	Synth.new(\Feedback, [
		\ch, i,
		\in, mic_ins[i],
		\out, audio_bus.index + i,
		\slope_bus, fbcontrol_bus.index + (2*i),
		\toggle_bus, fbcontrol_bus.index + (2*i) + 1,
		\bp_bus, bp_bus.index + i,
		\sens_bus, sens_bus.index + i
	], fx_group);
});

// make volume control units
// mixer through
Synth.new(\Volume, [
	\in, mixer_in,
	\num_in, 2,
	\out, main_out,
	\vol_bus, allvol_bus.index
], vol_group);

// mics to speaker
Synth.new(\Volume, [
	\in, audio_bus.index,
	\num_in, 2,
	\out, speaker_out,
	\vol_bus, allvol_bus.index + 1
], vol_group);

// mics to mixer
Synth.new(\Volume, [
	\in, audio_bus.index,
	\num_in, 2,
	\out, mixer_out,
	\vol_bus, allvol_bus.index + 2
], vol_group);

)

(
MIDIIn.connectAll;

MIDIFunc.cc({arg ...args;
	args.postln;
});
)