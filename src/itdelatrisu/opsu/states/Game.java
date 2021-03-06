/*
 * opsu! - an open-source osu! client
 * Copyright (C) 2014, 2015 Jeffrey Han
 *
 * opsu! is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * opsu! is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with opsu!.  If not, see <http://www.gnu.org/licenses/>.
 */

package itdelatrisu.opsu.states;

import itdelatrisu.opsu.ErrorHandler;
import itdelatrisu.opsu.GameData;
import itdelatrisu.opsu.GameImage;
import itdelatrisu.opsu.GameMod;
import itdelatrisu.opsu.MenuButton;
import itdelatrisu.opsu.Opsu;
import itdelatrisu.opsu.Options;
import itdelatrisu.opsu.OsuFile;
import itdelatrisu.opsu.OsuHitObject;
import itdelatrisu.opsu.OsuTimingPoint;
import itdelatrisu.opsu.ScoreData;
import itdelatrisu.opsu.UI;
import itdelatrisu.opsu.Utils;
import itdelatrisu.opsu.audio.HitSound;
import itdelatrisu.opsu.audio.MusicController;
import itdelatrisu.opsu.audio.SoundController;
import itdelatrisu.opsu.audio.SoundEffect;
import itdelatrisu.opsu.db.ScoreDB;
import itdelatrisu.opsu.objects.Circle;
import itdelatrisu.opsu.objects.HitObject;
import itdelatrisu.opsu.objects.Slider;
import itdelatrisu.opsu.objects.Spinner;

import java.io.File;
import java.util.Stack;
import java.util.concurrent.TimeUnit;

import org.lwjgl.input.Keyboard;
import org.newdawn.slick.Animation;
import org.newdawn.slick.Color;
import org.newdawn.slick.GameContainer;
import org.newdawn.slick.Graphics;
import org.newdawn.slick.Image;
import org.newdawn.slick.Input;
import org.newdawn.slick.SlickException;
import org.newdawn.slick.state.BasicGameState;
import org.newdawn.slick.state.StateBasedGame;
import org.newdawn.slick.state.transition.EmptyTransition;
import org.newdawn.slick.state.transition.FadeInTransition;
import org.newdawn.slick.state.transition.FadeOutTransition;

/**
 * "Game" state.
 */
public class Game extends BasicGameState {
	/** Game restart states. */
	public enum Restart {
		/** No restart. */
		FALSE,
		/** First time loading the song. */
		NEW,
		/** Manual retry. */
		MANUAL,
		/** Health is zero: no-continue/force restart. */
		LOSE;
	}

	/** Minimum time before start of song, in milliseconds, to process skip-related actions. */
	private static final int SKIP_OFFSET = 2000;

	/** The associated OsuFile object. */
	private OsuFile osu;

	/** The associated GameData object. */
	private GameData data;

	/** Current hit object index in OsuHitObject[] array. */
	private int objectIndex = 0;

	/** The map's HitObjects, indexed by objectIndex. */
	private HitObject[] hitObjects;

	/** Delay time, in milliseconds, before song starts. */
	private int leadInTime;

	/** Hit object approach time, in milliseconds. */
	private int approachTime;

	/** Time offsets for obtaining each hit result (indexed by HIT_* constants). */
	private int[] hitResultOffset;

	/** Current restart state. */
	private Restart restart;

	/** Current break index in breaks ArrayList. */
	private int breakIndex;

	/** Break start time (0 if not in break). */
	private int breakTime = 0;

	/** Whether the break sound has been played. */
	private boolean breakSound;

	/** Skip button (displayed at song start, when necessary). */
	private MenuButton skipButton;

	/** Current timing point index in timingPoints ArrayList. */
	private int timingPointIndex;

	/** Current beat lengths (base value and inherited value). */
	private float beatLengthBase, beatLength;

	/** Whether the countdown sound has been played. */
	private boolean
		countdownReadySound, countdown3Sound, countdown1Sound,
		countdown2Sound, countdownGoSound;

	/** Mouse coordinates before game paused. */
	private int pausedMouseX = -1, pausedMouseY = -1;

	/** Track position when game paused. */
	private int pauseTime = -1;

	/** Value for handling hitCircleSelect pulse effect (expanding, alpha level). */
	private float pausePulse;

	/** Whether a checkpoint has been loaded during this game. */
	private boolean checkpointLoaded = false;

	/** Number of deaths, used if "Easy" mod is enabled. */
	private byte deaths = 0;

	/** Track position at death, used if "Easy" mod is enabled. */
	private int deathTime = -1;

	/** Number of retries. */
	private int retries = 0;

	// game-related variables
	private GameContainer container;
	private StateBasedGame game;
	private Input input;
	private int state;

	public Game(int state) {
		this.state = state;
	}

	@Override
	public void init(GameContainer container, StateBasedGame game)
			throws SlickException {
		this.container = container;
		this.game = game;
		input = container.getInput();

		int width = container.getWidth();
		int height = container.getHeight();

		// create the associated GameData object
		data = new GameData(width, height);
	}

	@Override
	public void render(GameContainer container, StateBasedGame game, Graphics g)
			throws SlickException {
		int width = container.getWidth();
		int height = container.getHeight();

		// background
		g.setBackground(Color.black);
		float dimLevel = Options.getBackgroundDim();
		if (Options.isDefaultPlayfieldForced() || !osu.drawBG(width, height, dimLevel, false)) {
			Image playfield = GameImage.PLAYFIELD.getImage();
			playfield.setAlpha(dimLevel);
			playfield.draw();
			playfield.setAlpha(1f);
		}

		int trackPosition = MusicController.getPosition();
		if (pauseTime > -1)  // returning from pause screen
			trackPosition = pauseTime;
		else if (deathTime > -1)  // "Easy" mod: health bar increasing
			trackPosition = deathTime;
		int firstObjectTime = osu.objects[0].getTime();
		int timeDiff = firstObjectTime - trackPosition;

		// checkpoint
		if (checkpointLoaded) {
			int checkpoint = Options.getCheckpoint();
			String checkpointText = String.format(
					"Playing from checkpoint at %02d:%02d.",
					TimeUnit.MILLISECONDS.toMinutes(checkpoint),
					TimeUnit.MILLISECONDS.toSeconds(checkpoint) -
					TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(checkpoint))
			);
			Utils.FONT_MEDIUM.drawString(
					(width - Utils.FONT_MEDIUM.getWidth(checkpointText)) / 2,
					height - 15 - Utils.FONT_MEDIUM.getLineHeight(),
					checkpointText, Color.white
			);
		}

		// break periods
		if (osu.breaks != null && breakIndex < osu.breaks.size()) {
			if (breakTime > 0) {
				int endTime = osu.breaks.get(breakIndex);
				int breakLength = endTime - breakTime;

				// letterbox effect (black bars on top/bottom)
				if (osu.letterboxInBreaks && breakLength >= 4000) {
					g.setColor(Color.black);
					g.fillRect(0, 0, width, height * 0.125f);
					g.fillRect(0, height * 0.875f, width, height * 0.125f);
				}

				data.drawGameElements(g, true, objectIndex == 0);

				if (breakLength >= 8000 &&
					trackPosition - breakTime > 2000 &&
					trackPosition - breakTime < 5000) {
					// show break start
					if (data.getHealth() >= 50) {
						GameImage.SECTION_PASS.getImage().drawCentered(width / 2f, height / 2f);
						if (!breakSound) {
							SoundController.playSound(SoundEffect.SECTIONPASS);
							breakSound = true;
						}
					} else {
						GameImage.SECTION_FAIL.getImage().drawCentered(width / 2f, height / 2f);
						if (!breakSound) {
							SoundController.playSound(SoundEffect.SECTIONFAIL);
							breakSound = true;
						}
					}
				} else if (breakLength >= 4000) {
					// show break end (flash twice for 500ms)
					int endTimeDiff = endTime - trackPosition;
					if ((endTimeDiff > 1500 && endTimeDiff < 2000) ||
						(endTimeDiff > 500 && endTimeDiff < 1000)) {
						Image arrow = GameImage.WARNINGARROW.getImage();
						arrow.setRotation(0);
						arrow.draw(width * 0.15f, height * 0.15f);
						arrow.draw(width * 0.15f, height * 0.75f);
						arrow.setRotation(180);
						arrow.draw(width * 0.75f, height * 0.15f);
						arrow.draw(width * 0.75f, height * 0.75f);
					}
				}

				if (GameMod.AUTO.isActive())
					GameImage.UNRANKED.getImage().drawCentered(width / 2, height * 0.077f);
				UI.draw(g);
				return;
			}
		}

		// game elements
		data.drawGameElements(g, false, objectIndex == 0);

		// skip beginning
		if (objectIndex == 0 &&
		    trackPosition < osu.objects[0].getTime() - SKIP_OFFSET)
			skipButton.draw();

		// show retries
		if (retries >= 2 && timeDiff >= -1000) {
			int retryHeight = Math.max(
					GameImage.SCOREBAR_BG.getImage().getHeight(),
					GameImage.SCOREBAR_KI.getImage().getHeight()
			);
			float oldAlpha = Utils.COLOR_WHITE_FADE.a;
			if (timeDiff < -500)
				Utils.COLOR_WHITE_FADE.a = (1000 + timeDiff) / 500f;
			Utils.FONT_MEDIUM.drawString(
					2 + (width / 100), retryHeight,
					String.format("%d retries and counting...", retries),
					Utils.COLOR_WHITE_FADE
			);
			Utils.COLOR_WHITE_FADE.a = oldAlpha;
		}

		if (isLeadIn())
			trackPosition = (leadInTime - Options.getMusicOffset()) * -1;  // render approach circles during song lead-in

		// countdown
		if (osu.countdown > 0) {  // TODO: implement half/double rate settings
			timeDiff = firstObjectTime - trackPosition;
			if (timeDiff >= 500 && timeDiff < 3000) {
				if (timeDiff >= 1500) {
					GameImage.COUNTDOWN_READY.getImage().drawCentered(width / 2, height / 2);
					if (!countdownReadySound) {
						SoundController.playSound(SoundEffect.READY);
						countdownReadySound = true;
					}
				}
				if (timeDiff < 2000) {
					GameImage.COUNTDOWN_3.getImage().draw(0, 0);
					if (!countdown3Sound) {
						SoundController.playSound(SoundEffect.COUNT3);
						countdown3Sound = true;
					}
				}
				if (timeDiff < 1500) {
					GameImage.COUNTDOWN_2.getImage().draw(width - GameImage.COUNTDOWN_2.getImage().getWidth(), 0);
					if (!countdown2Sound) {
						SoundController.playSound(SoundEffect.COUNT2);
						countdown2Sound = true;
					}
				}
				if (timeDiff < 1000) {
					GameImage.COUNTDOWN_1.getImage().drawCentered(width / 2, height / 2);
					if (!countdown1Sound) {
						SoundController.playSound(SoundEffect.COUNT1);
						countdown1Sound = true;
					}
				}
			} else if (timeDiff >= -500 && timeDiff < 500) {
				Image go = GameImage.COUNTDOWN_GO.getImage();
				go.setAlpha((timeDiff < 0) ? 1 - (timeDiff / -1000f) : 1);
				go.drawCentered(width / 2, height / 2);
				if (!countdownGoSound) {
					SoundController.playSound(SoundEffect.GO);
					countdownGoSound = true;
				}
			}
		}

		// draw hit objects in reverse order, or else overlapping objects are unreadable
		Stack<Integer> stack = new Stack<Integer>();
		for (int i = objectIndex; i < hitObjects.length && osu.objects[i].getTime() < trackPosition + approachTime; i++)
			stack.add(i);

		while (!stack.isEmpty())
			hitObjects[stack.pop()].draw(trackPosition, stack.isEmpty(), g);

		// draw OsuHitObjectResult objects
		data.drawHitResults(trackPosition);

		if (GameMod.AUTO.isActive())
			GameImage.UNRANKED.getImage().drawCentered(width / 2, height * 0.077f);

		// returning from pause screen
		if (pauseTime > -1 && pausedMouseX > -1 && pausedMouseY > -1) {
			// darken the screen
			g.setColor(Utils.COLOR_BLACK_ALPHA);
			g.fillRect(0, 0, width, height);

			// draw glowing hit select circle and pulse effect
			int circleRadius = GameImage.HITCIRCLE.getImage().getWidth();
			Image cursorCircle = GameImage.HITCIRCLE_SELECT.getImage().getScaledCopy(circleRadius, circleRadius);
			cursorCircle.setAlpha(1.0f);
			cursorCircle.drawCentered(pausedMouseX, pausedMouseY);
			Image cursorCirclePulse = cursorCircle.getScaledCopy(1f + pausePulse);
			cursorCirclePulse.setAlpha(1f - pausePulse);
			cursorCirclePulse.drawCentered(pausedMouseX, pausedMouseY);
		}

		UI.draw(g);
	}

	@Override
	public void update(GameContainer container, StateBasedGame game, int delta)
			throws SlickException {
		UI.update(delta);
		int mouseX = input.getMouseX(), mouseY = input.getMouseY();
		skipButton.hoverUpdate(delta, mouseX, mouseY);

		if (isLeadIn()) {  // stop updating during song lead-in
			leadInTime -= delta;
			if (!isLeadIn())
				MusicController.resume();
			return;
		}

		// returning from pause screen: must click previous mouse position
		if (pauseTime > -1) {
			// paused during lead-in or break, or "relax" or "autopilot": continue immediately
			if ((pausedMouseX < 0 && pausedMouseY < 0) ||
			    (GameMod.RELAX.isActive() || GameMod.AUTOPILOT.isActive())) {
				pauseTime = -1;
				if (!isLeadIn())
					MusicController.resume();
			}

			// focus lost: go back to pause screen
			else if (!container.hasFocus()) {
				game.enterState(Opsu.STATE_GAMEPAUSEMENU);
				pausePulse = 0f;
			}

			// advance pulse animation
			else {
				pausePulse += delta / 750f;
				if (pausePulse > 1f)
					pausePulse = 0f;
			}
			return;
		}

		// "Easy" mod: multiple "lives"
		if (GameMod.EASY.isActive() && deathTime > -1) {
			if (data.getHealth() < 99f)
				data.changeHealth(delta / 10f);
			else {
				MusicController.resume();
				deathTime = -1;
			}
		}

		data.updateDisplays(delta);

		// map complete!
		if (objectIndex >= hitObjects.length || (MusicController.trackEnded() && objectIndex > 0)) {
			// track ended before last object was processed: force a hit result
			if (MusicController.trackEnded() && objectIndex < hitObjects.length)
				hitObjects[objectIndex].update(true, delta, mouseX, mouseY);

			if (checkpointLoaded)  // if checkpoint used, skip ranking screen
				game.closeRequested();
			else {  // go to ranking screen
				((GameRanking) game.getState(Opsu.STATE_GAMERANKING)).setGameData(data);
				ScoreData score = data.getScoreData(osu);
				if (!GameMod.AUTO.isActive() && !GameMod.RELAX.isActive() && !GameMod.AUTOPILOT.isActive())
					ScoreDB.addScore(score);
				game.enterState(Opsu.STATE_GAMERANKING, new FadeOutTransition(Color.black), new FadeInTransition(Color.black));
			}
			return;
		}

		int trackPosition = MusicController.getPosition();

		// timing points
		if (timingPointIndex < osu.timingPoints.size()) {
			OsuTimingPoint timingPoint = osu.timingPoints.get(timingPointIndex);
			if (trackPosition >= timingPoint.getTime()) {
				if (!timingPoint.isInherited())
					beatLengthBase = beatLength = timingPoint.getBeatLength();
				else
					beatLength = beatLengthBase * timingPoint.getSliderMultiplier();
				HitSound.setDefaultSampleSet(timingPoint.getSampleType());
				SoundController.setSampleVolume(timingPoint.getSampleVolume());
				timingPointIndex++;
			}
		}

		// song beginning
		if (objectIndex == 0 && trackPosition < osu.objects[0].getTime())
			return;  // nothing to do here

		// break periods
		if (osu.breaks != null && breakIndex < osu.breaks.size()) {
			int breakValue = osu.breaks.get(breakIndex);
			if (breakTime > 0) {  // in a break period
				if (trackPosition < breakValue)
					return;
				else {
					// break is over
					breakTime = 0;
					breakIndex++;
				}
			} else if (trackPosition >= breakValue) {
				// start a break
				breakTime = breakValue;
				breakSound = false;
				breakIndex++;
				return;
			}
		}

		// pause game if focus lost
		if (!container.hasFocus() && !GameMod.AUTO.isActive()) {
			if (pauseTime < 0) {
				pausedMouseX = mouseX;
				pausedMouseY = mouseY;
				pausePulse = 0f;
			}
			if (MusicController.isPlaying() || isLeadIn())
				pauseTime = trackPosition;
			game.enterState(Opsu.STATE_GAMEPAUSEMENU);
		}

		// drain health
		data.changeHealth(delta * -1 * GameData.HP_DRAIN_MULTIPLIER);
		if (!data.isAlive()) {
			// "Easy" mod
			if (GameMod.EASY.isActive() && !GameMod.SUDDEN_DEATH.isActive()) {
				deaths++;
				if (deaths < 3) {
					deathTime = trackPosition;
					MusicController.pause();
					return;
				}
			}

			// game over, force a restart
			restart = Restart.LOSE;
			game.enterState(Opsu.STATE_GAMEPAUSEMENU);
		}

		// update objects (loop in unlikely event of any skipped indexes)
		while (objectIndex < hitObjects.length && trackPosition > osu.objects[objectIndex].getTime()) {
			// check if we've already passed the next object's start time
			boolean overlap = (objectIndex + 1 < hitObjects.length &&
					trackPosition > osu.objects[objectIndex + 1].getTime() - hitResultOffset[GameData.HIT_300]);

			// update hit object and check completion status
			if (hitObjects[objectIndex].update(overlap, delta, mouseX, mouseY))
				objectIndex++;  // done, so increment object index
			else
				break;
		}
	}

	@Override
	public int getID() { return state; }

	@Override
	public void keyPressed(int key, char c) {
		int trackPosition = MusicController.getPosition();

		// game keys
		if (!Keyboard.isRepeatEvent()) {
			if (key == Options.getGameKeyLeft())
				gameKeyPressed(Input.MOUSE_LEFT_BUTTON, input.getMouseX(), input.getMouseY());
			else if (key == Options.getGameKeyRight())
				gameKeyPressed(Input.MOUSE_RIGHT_BUTTON, input.getMouseX(), input.getMouseY());
		}

		switch (key) {
		case Input.KEY_ESCAPE:
			// "auto" mod: go back to song menu
			if (GameMod.AUTO.isActive()) {
				game.closeRequested();
				break;
			}

			// pause game
			if (pauseTime < 0 && breakTime <= 0 && trackPosition >= osu.objects[0].getTime()) {
				pausedMouseX = input.getMouseX();
				pausedMouseY = input.getMouseY();
				pausePulse = 0f;
			}
			if (MusicController.isPlaying() || isLeadIn())
				pauseTime = trackPosition;
			game.enterState(Opsu.STATE_GAMEPAUSEMENU, new EmptyTransition(), new FadeInTransition(Color.black));
			break;
		case Input.KEY_SPACE:
			// skip intro
			skipIntro();
			break;
		case Input.KEY_R:
			// restart
			if (input.isKeyDown(Input.KEY_RCONTROL) || input.isKeyDown(Input.KEY_LCONTROL)) {
				try {
					if (trackPosition < osu.objects[0].getTime())
						retries--;  // don't count this retry (cancel out later increment)
					restart = Restart.MANUAL;
					enter(container, game);
					skipIntro();
				} catch (SlickException e) {
					ErrorHandler.error("Failed to restart game.", e, false);
				}
			}
			break;
		case Input.KEY_S:
			// save checkpoint
			if (input.isKeyDown(Input.KEY_RCONTROL) || input.isKeyDown(Input.KEY_LCONTROL)) {
				if (isLeadIn())
					break;

				int position = (pauseTime > -1) ? pauseTime : trackPosition;
				if (Options.setCheckpoint(position / 1000)) {
					SoundController.playSound(SoundEffect.MENUCLICK);
					UI.sendBarNotification("Checkpoint saved.");
				}
			}
			break;
		case Input.KEY_L:
			// load checkpoint
			if (input.isKeyDown(Input.KEY_RCONTROL) || input.isKeyDown(Input.KEY_LCONTROL)) {
				int checkpoint = Options.getCheckpoint();
				if (checkpoint == 0 || checkpoint > osu.endTime)
					break;  // invalid checkpoint
				try {
					restart = Restart.MANUAL;
					enter(container, game);
					checkpointLoaded = true;
					if (isLeadIn()) {
						leadInTime = 0;
						MusicController.resume();
					}
					SoundController.playSound(SoundEffect.MENUHIT);
					UI.sendBarNotification("Checkpoint loaded.");

					// skip to checkpoint
					MusicController.setPosition(checkpoint);
					while (objectIndex < hitObjects.length &&
							osu.objects[objectIndex++].getTime() <= checkpoint)
						;
					objectIndex--;
				} catch (SlickException e) {
					ErrorHandler.error("Failed to load checkpoint.", e, false);
				}
			}
			break;
		case Input.KEY_UP:
			UI.changeVolume(1);
			break;
		case Input.KEY_DOWN:
			UI.changeVolume(-1);
			break;
		case Input.KEY_F7:
			Options.setNextFPS(container);
			break;
		case Input.KEY_F10:
			Options.toggleMouseDisabled();
			break;
		case Input.KEY_F12:
			Utils.takeScreenShot();
			break;
		}
	}

	@Override
	public void mousePressed(int button, int x, int y) {
		if (Options.isMouseDisabled())
			return;

		// mouse wheel: pause the game
		if (button == Input.MOUSE_MIDDLE_BUTTON && !Options.isMouseWheelDisabled()) {
			int trackPosition = MusicController.getPosition();
			if (pauseTime < 0 && breakTime <= 0 && trackPosition >= osu.objects[0].getTime()) {
				pausedMouseX = x;
				pausedMouseY = y;
				pausePulse = 0f;
			}
			if (MusicController.isPlaying() || isLeadIn())
				pauseTime = trackPosition;
			game.enterState(Opsu.STATE_GAMEPAUSEMENU, new EmptyTransition(), new FadeInTransition(Color.black));
			return;
		}

		gameKeyPressed(button, x, y);
	}

	/**
	 * Handles a game key pressed event.
	 * @param button the index of the button pressed
	 * @param x the mouse x coordinate
	 * @param y the mouse y coordinate
	 */
	private void gameKeyPressed(int button, int x, int y) {
		// returning from pause screen
		if (pauseTime > -1) {
			double distance = Math.hypot(pausedMouseX - x, pausedMouseY - y);
			int circleRadius = GameImage.HITCIRCLE.getImage().getWidth() / 2;
			if (distance < circleRadius) {
				// unpause the game
				pauseTime = -1;
				pausedMouseX = -1;
				pausedMouseY = -1;
				if (!isLeadIn())
					MusicController.resume();
			}
			return;
		}

		if (objectIndex >= hitObjects.length)  // nothing left to do here
			return;

		OsuHitObject hitObject = osu.objects[objectIndex];

		// skip beginning
		if (skipButton.contains(x, y)) {
			if (skipIntro())
				return;  // successfully skipped
		}

		// "auto" and "relax" mods: ignore user actions
		if (GameMod.AUTO.isActive() || GameMod.RELAX.isActive())
			return;

		// circles
		if (hitObject.isCircle() && hitObjects[objectIndex].mousePressed(x, y))
			objectIndex++;  // circle hit

		// sliders
		else if (hitObject.isSlider())
			hitObjects[objectIndex].mousePressed(x, y);
	}

	@Override
	public void mouseWheelMoved(int newValue) {
		if (Options.isMouseWheelDisabled() || Options.isMouseDisabled())
			return;

		UI.changeVolume((newValue < 0) ? -1 : 1);
	}

	@Override
	public void enter(GameContainer container, StateBasedGame game)
			throws SlickException {
		UI.enter();
		if (restart == Restart.NEW)
			osu = MusicController.getOsuFile();

		if (osu == null || osu.objects == null)
			throw new RuntimeException("Running game with no OsuFile loaded.");

		// grab the mouse (not working for touchscreen)
//		container.setMouseGrabbed(true);

		// restart the game
		if (restart != Restart.FALSE) {
			if (restart == Restart.NEW) {
				// new game
				loadImages();
				setMapModifiers();
				retries = 0;
			} else {
				// retry
				retries++;
			}

			// reset game data
			resetGameData();

			// needs to play before setting position to resume without lag later
			MusicController.play(false);
			MusicController.setPosition(0);
			MusicController.pause();

			// initialize object maps
			for (int i = 0; i < osu.objects.length; i++) {
				OsuHitObject hitObject = osu.objects[i];

				// is this the last note in the combo?
				boolean comboEnd = false;
				if (i + 1 < osu.objects.length && osu.objects[i + 1].isNewCombo())
					comboEnd = true;

				Color color = osu.combo[hitObject.getComboIndex()];
				if (hitObject.isCircle())
					hitObjects[i] = new Circle(hitObject, this, data, color, comboEnd);
				else if (hitObject.isSlider())
					hitObjects[i] = new Slider(hitObject, this, data, color, comboEnd);
				else if (hitObject.isSpinner())
					hitObjects[i] = new Spinner(hitObject, this, data);
			}

			// load the first timingPoint
			if (!osu.timingPoints.isEmpty()) {
				OsuTimingPoint timingPoint = osu.timingPoints.get(0);
				if (!timingPoint.isInherited()) {
					beatLengthBase = beatLength = timingPoint.getBeatLength();
					HitSound.setDefaultSampleSet(timingPoint.getSampleType());
					SoundController.setSampleVolume(timingPoint.getSampleVolume());
					timingPointIndex++;
				}
			}

			leadInTime = osu.audioLeadIn + approachTime;
			restart = Restart.FALSE;
		}

		skipButton.resetHover();
	}

//	@Override
//	public void leave(GameContainer container, StateBasedGame game)
//			throws SlickException {
//		container.setMouseGrabbed(false);
//	}

	/**
	 * Resets all game data and structures.
	 */
	public void resetGameData() {
		hitObjects = new HitObject[osu.objects.length];
		data.clear();
		objectIndex = 0;
		breakIndex = 0;
		breakTime = 0;
		breakSound = false;
		timingPointIndex = 0;
		beatLengthBase = beatLength = 1;
		pauseTime = -1;
		pausedMouseX = -1;
		pausedMouseY = -1;
		countdownReadySound = false;
		countdown3Sound = false;
		countdown1Sound = false;
		countdown2Sound = false;
		countdownGoSound = false;
		checkpointLoaded = false;
		deaths = 0;
		deathTime = -1;

		System.gc();
	}

	/**
	 * Skips the beginning of a track.
	 * @return true if skipped, false otherwise
	 */
	private boolean skipIntro() {
		int firstObjectTime = osu.objects[0].getTime();
		int trackPosition = MusicController.getPosition();
		if (objectIndex == 0 &&
			trackPosition < firstObjectTime - SKIP_OFFSET) {
			if (isLeadIn()) {
				leadInTime = 0;
				MusicController.resume();
			}
			MusicController.setPosition(firstObjectTime - SKIP_OFFSET);
			SoundController.playSound(SoundEffect.MENUHIT);
			return true;
		}
		return false;
	}

	/**
	 * Loads all game images.
	 */
	private void loadImages() {
		int width = container.getWidth();
		int height = container.getHeight();

		// set images
		File parent = osu.getFile().getParentFile();
		for (GameImage img : GameImage.values()) {
			if (img.isSkinnable()) {
				img.setDefaultImage();
				img.setSkinImage(parent);
			}
		}

		// skip button
		if (GameImage.SKIP.getImages() != null) {
			Animation skip = GameImage.SKIP.getAnimation(120);
			skipButton = new MenuButton(skip, width - skip.getWidth() / 2f, height - (skip.getHeight() / 2f));
		} else {
			Image skip = GameImage.SKIP.getImage();
			skipButton = new MenuButton(skip, width - skip.getWidth() / 2f, height - (skip.getHeight() / 2f));
		}
		skipButton.setHoverExpand(1.1f, MenuButton.Expand.UP_LEFT);

		// load other images...
		((GamePauseMenu) game.getState(Opsu.STATE_GAMEPAUSEMENU)).loadImages();
		data.loadImages();
	}

	/**
	 * Set map modifiers.
	 */
	private void setMapModifiers() {
		// map-based properties, re-initialized each game
		float circleSize = osu.circleSize;
		float approachRate = osu.approachRate;
		float overallDifficulty = osu.overallDifficulty;
		float HPDrainRate = osu.HPDrainRate;

		// "Hard Rock" modifiers
		if (GameMod.HARD_ROCK.isActive()) {
			circleSize = Math.min(circleSize * 1.4f, 10);
			approachRate = Math.min(approachRate * 1.4f, 10);
			overallDifficulty = Math.min(overallDifficulty * 1.4f, 10);
			HPDrainRate = Math.min(HPDrainRate * 1.4f, 10);
		}

		// "Easy" modifiers
		else if (GameMod.EASY.isActive()) {
			circleSize /= 2f;
			approachRate /= 2f;
			overallDifficulty /= 2f;
			HPDrainRate /= 2f;
		}

		// fixed difficulty overrides
		if (Options.getFixedCS() > 0f)
			circleSize = Options.getFixedCS();
		if (Options.getFixedAR() > 0f)
			approachRate = Options.getFixedAR();
		if (Options.getFixedOD() > 0f)
			overallDifficulty = Options.getFixedOD();
		if (Options.getFixedHP() > 0f)
			HPDrainRate = Options.getFixedHP();

		// initialize objects
		Circle.init(container, circleSize);
		Slider.init(container, circleSize, osu);
		Spinner.init(container);

		// approachRate (hit object approach time)
		if (approachRate < 5)
			approachTime = (int) (1800 - (approachRate * 120));
		else
			approachTime = (int) (1200 - ((approachRate - 5) * 150));

		// overallDifficulty (hit result time offsets)
		hitResultOffset = new int[GameData.HIT_MAX];
		hitResultOffset[GameData.HIT_300]  = (int) (78 - (overallDifficulty * 6));
		hitResultOffset[GameData.HIT_100]  = (int) (138 - (overallDifficulty * 8));
		hitResultOffset[GameData.HIT_50]   = (int) (198 - (overallDifficulty * 10));
		hitResultOffset[GameData.HIT_MISS] = (int) (500 - (overallDifficulty * 10));

		// HPDrainRate (health change), overallDifficulty (scoring)
		data.setDrainRate(HPDrainRate);
		data.setDifficulty(overallDifficulty);
		data.setHitResultOffset(hitResultOffset);
	}

	/**
	 * Sets/returns whether entering the state will restart it.
	 */
	public void setRestart(Restart restart) { this.restart = restart; }
	public Restart getRestart() { return restart; }

	/**
	 * Returns whether or not the track is in the lead-in time state.
	 */
	public boolean isLeadIn() { return leadInTime > 0; }

	/**
	 * Returns the object approach time, in milliseconds.
	 */
	public int getApproachTime() { return approachTime; }

	/**
	 * Returns an array of hit result offset times, in milliseconds (indexed by GameData.HIT_* constants).
	 */
	public int[] getHitResultOffsets() { return hitResultOffset; }

	/**
	 * Returns the beat length.
	 */
	public float getBeatLength() { return beatLength; }

	/**
	 * Returns the slider multiplier given by the current timing point.
	 */
	public float getTimingPointMultiplier() { return beatLength / beatLengthBase; }
}
