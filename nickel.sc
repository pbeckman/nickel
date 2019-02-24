Nickel {
	*new {arg s, midi_chan, gui_chans, mic_ins, speaker_out, mixer_out;

		var num_chan = size(mic_ins);
		var audio_bus = Bus.audio(s, gui_chans);
		var vol_bus = Bus.control(s, 3);
		var fbtoggle_bus = Bus.control(s, gui_chans);
		var fbslope_bus = Bus.control(s, gui_chans);
		var arf_bus = Bus.control(s, 3);
		var env_bus = Bus.control(s, gui_chans);
		var bp_bus = Bus.control(s, gui_chans);
		var width_bus = Bus.control(s, gui_chans);
		var smooth_bus = Bus.control(s);
		var jump_bus = Bus.control(s);
		var trigger_bus = Bus.control(s, gui_chans*gui_chans);
		var sens_bus = Bus.control(s, gui_chans);
		var fx_group = Group.tail(s);
		var vol_group = Group.tail(s);
		var midi_out = MIDIOut.newByName("TouchOSC Bridge", "TouchOSC Bridge");

		// GUI

		// reset GUI to zero
		10.do({
			127.do({arg i;
				i.postln;
				midi_out.control(0, i, 0);
			});
		});

		/*gui_mirror = Routine.new({
		inf.do({
		gui_bus.getn(num_chan, {arg bps;
		bps.postln;
		num_chan.do({arg i;
		midi_out.control(0, 60 + i, bps[i])
		});
		});
		delta.wait;
		});
		}).play;*/

		// DEFINE PROCESSING UNITS

		SynthDef.new(\Feedback, {arg ch, in, out,
			slope_bus, toggle_bus, sens_bus,
			bp_bus, width_bus, smooth_bus,
			arf_bus, env_bus;
			var signal = SoundIn.ar(in);
			// track amplitude onsets
			var onset = Coyote.kr(
				signal,
				fastMul:LinLin.kr(In.kr(sens_bus), 0, 127, 0.01, 0.9)
			);
			// envelope parameters
			var arf = In.kr(arf_bus, 3);
			var attack = LinLin.kr(arf[0], 0, 127, 0.0, 10.0);
			var release = LinLin.kr(arf[1], 0, 127, 0.0, 20.0);
			var floor = LinLin.kr(arf[2], 0, 127, 0.0, 1.0);
			// generate envelope when onset detected
			var env = EnvGen.kr(
				Env.perc(attack, release),
				onset
			);
			// determines feedback presence
			var slope = Select.kr(In.kr(toggle_bus) > 0, [
				2.0,
				LinLin.kr(In.kr(slope_bus), 0, 127, 0.7, 0.2)
			]);
			// determines lag time in bandpass frequency changes
			var smooth = LinLin.kr(In.kr(smooth_bus), 0, 127, 0.0, 60.0);
			// lagged bandpass frequency
			var bp = Lag.kr(
				LinExp.kr(In.kr(bp_bus), 0, 127, 20, 20000),
				smooth
			);
			// bandpass width
			var width = LinLin.kr(In.kr(width_bus), 0, 127, 0.2, 5.0);

			// send trigger when onset detected
			SendTrig.kr(onset, 0, ch);

			signal = BBandPass.ar(signal,
				freq:bp.poll,
				bw:width
			);

			// compress signal from above and below to induce controlled feedback
			signal = Compander.ar(signal,
				control:signal,
				thresh:0.5,
				slopeBelow:slope,
				slopeAbove:0.2,
				clampTime:0.01,
				relaxTime:0.01
			);
			// multiply signal by triggered envelope minus the floor
			signal = signal * (floor + ((1.0-floor) * env));
			// limit signal for good measure
			signal = Limiter.ar(
				signal,
				1.0,
				0.01
			);

			Out.ar(out, signal);
		}).load(s);

		SynthDef.new(\Volume, {arg in, out, vol_bus;
			var signal = In.ar(in, num_chan);
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
			vol_bus.setAt(cc_num, val);
		}, [0,1,2], midi_chan);

		// write feedback slope to fbslope_bus
		MIDIFunc.cc({arg val, cc_num;
			fbslope_bus.setAt(cc_num - 10, val);
		}, (10..(10+gui_chans)), midi_chan);

		// write feedback toggles to fbtoggle_bus
		MIDIFunc.cc({arg val, cc_num;
			fbtoggle_bus.setAt(cc_num - 15, val);
		}, (15..(15+gui_chans)), midi_chan);

		// write trigger toggles to trigger_bus
		MIDIFunc.cc({arg val, cc_num;
			trigger_bus.setAt(cc_num - 20, val);
		}, (20..(20+(gui_chans*gui_chans))), midi_chan);

		// write sensitivity sliders to sens_bus
		MIDIFunc.cc({arg val, cc_num;
			sens_bus.setAt(cc_num - 40, val);
		}, (40..(40+gui_chans)), midi_chan);

		// write attack/release/floor sliders to arf_bus
		MIDIFunc.cc({arg val, cc_num;
			arf_bus.setAt(cc_num - 80, val);
		}, (80..83), midi_chan);

		// write bandpass freq sliders to bp_bus
		MIDIFunc.cc({arg val, cc_num;
			bp_bus.setAt(cc_num - 60, val);
		}, (60..(60+gui_chans)), midi_chan);

		// write bandpass width sliders to width_bus
		MIDIFunc.cc({arg val, cc_num;
			width_bus.setAt(cc_num - 65, val);
		}, (65..(65+gui_chans)), midi_chan);

		// write smoothing slider to smooth_bus
		MIDIFunc.cc({arg val, cc_num;
			smooth_bus.set(val);
		}, 70, midi_chan);

		// write jumpiness slider to jump_bus
		MIDIFunc.cc({arg val, cc_num;
			jump_bus.set(val);
		}, 71, midi_chan);

		// randomize bandpass for toggled channels on trigger
		OSCFunc({arg msg;
			var ch = msg[3];
			var val;
			// get jumpiness level
			jump_bus.get({arg jump;
				// get current bp frequency
				bp_bus.getn(gui_chans, {arg bps;
					// get triggers
					trigger_bus.getn(gui_chans*gui_chans, {arg triggers;
						triggers = triggers[[ch, gui_chans+ch, (2*gui_chans)+ch, (3*gui_chans)+ch]];
						gui_chans.do({arg i;
							if(triggers[i] == 127, {
								val = rrand(
									max(0, bps[i] - jump),
									min(bps[i] + jump, 127)
								);
								bp_bus.setAt(i, val);
								midi_out.control(0, 60 + i, val);
							});
						});
					});
				});
			});
			midi_out.control(0, 50 + ch, rrand(0,127));
		},'/tr', s.addr);

		// CREATE PROCESSING UNITS

		// make feedback units
		num_chan.do({arg i;
			Synth.new(\Feedback, [
				\ch, i,
				\in, mic_ins[i],
				\out, audio_bus.index + i,
				\slope_bus, fbslope_bus.index + i,
				\toggle_bus, fbtoggle_bus.index + i,
				\arf_bus, arf_bus.index,
				\width_bus, width_bus.index + i,
				\bp_bus, bp_bus.index + i,
				\smooth_bus, smooth_bus.index,
				\sens_bus, sens_bus.index + i
			], fx_group);
		});

		// mics to speaker
		Synth.new(\Volume, [
			\in, audio_bus.index,
			\out, speaker_out,
			\mix, 1,
			\vol_bus, vol_bus.index + 1
		], vol_group);

		// mics to mixer
		Synth.new(\Volume, [
			\in, audio_bus.index,
			\out, mixer_out,
			\mix, 1,
			\vol_bus, vol_bus.index + 2
		], vol_group);
	}
}