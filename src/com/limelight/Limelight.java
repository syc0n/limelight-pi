package com.limelight;

import java.io.IOException;

import com.limelight.binding.PlatformBinding;
import com.limelight.input.EvdevHandler;
import com.limelight.input.GamepadMapping;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.http.NvHTTP;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Main class for Limelight-pi
 * @author Diego Waxemberg<br>
 * Cameron Gutman
 * Iwan Timmer
 */
public class Limelight implements NvConnectionListener {

	private String host;
	private NvConnection conn;
	private boolean connectionTerminating;

	/**
	 * Constructs a new instance based on the given host
	 * @param host can be hostname or IP address.
	 */
	public Limelight(String host) {
		this.host = host;
	}

	/*
	 * Creates a connection to the host and starts up the stream.
	 */
	private void startUp(StreamConfiguration streamConfig, List<String> inputs, String mappingFile, String audioDevice) {
		String vm = System.getProperties().getProperty("java.vm.name");
		if (!vm.contains("HotSpot")) {
			System.err.println("You are using a unsupported VM: " + vm);
			System.err.println("Please update to Oracle Java (Embedded) for better performances");
		}
	
		conn = new NvConnection(host, this, streamConfig);
		
		if (inputs.isEmpty()) {
			File input = new File("/dev/input");
			String[] events = input.list(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.startsWith("event");
				}
			});
			
			for (String event:events)
				inputs.add(new File(input, event).getAbsolutePath());
		}
		
		GamepadMapping mapping = null;
		if (mappingFile!=null) {
			try {
				mapping = new GamepadMapping(new File(mappingFile));
			} catch (IOException e) {
				displayError("Mapping", "Can't load gamepad mapping from " + mappingFile);
				System.exit(3);
			}
		} else
			mapping = new GamepadMapping();

		for (String input:inputs) {
			try {
				new EvdevHandler(conn, input, mapping).start();
			} catch (FileNotFoundException ex) {
				displayError("Input", "Input (" + input + ") could not be found");
				return;
			} catch (IOException ex) {
				displayError("Input", "Input (" + input + ") could not be read");
				displayError("Input", "Are you running as root?");
				return;
			}
		}
		
		conn.start(PlatformBinding.getDeviceName(), null,
				VideoDecoderRenderer.FLAG_PREFER_QUALITY,
				PlatformBinding.getAudioRenderer(audioDevice),
				PlatformBinding.getVideoDecoderRenderer());
	}
	
	/**
	 * Pair the device with the host
	 */
	private void pair() {
		String macAddress;
		try {
			macAddress = NvConnection.getMacAddressString();
		} catch (SocketException e) {
			e.printStackTrace();
			return;
		}

		if (macAddress == null) {
			displayError("Pair", "Couldn't find a MAC address");
			return;
		}

		NvHTTP httpConn;
	
		try {
			httpConn = new NvHTTP(InetAddress.getByName(host),
				macAddress, PlatformBinding.getDeviceName());
			try {
				if (httpConn.getPairState()) {
					displayError("Pair", "Already paired");
				} else {
					int session = httpConn.getSessionId();
					if (session == 0) {
						displayError("Pair", "Pairing was declined by the target");
					} else {
						displayMessage("Pairing was successful");
					}
				}
			} catch (IOException e) {
				displayError("Pair", e.getMessage());
			} catch (XmlPullParserException e) {
				displayError("Pair", e.getMessage());
			}
		} catch (UnknownHostException e1) {
			displayError("Pair", "Failed to resolve host");
		}
	}
	
	/**
	 * The entry point for the application. <br>
	 * Does some initializations and then creates the main frame.
	 * @param args unused.
	 */
	public static void main(String args[]) {
		String host = null;
		List<String> inputs = new ArrayList<String>();
		boolean pair = false;
		int resolution = 720;
		int refresh = 60;
		boolean parse = true;
		String mapping = null;
		String audio = "hw:0";
		
		for (int i = 0; i < args.length - 1; i++) {
			if (args[i].equals("-input")) {
				if (i + 1 < args.length) {
					inputs.add(args[i+1]);
					i++;
				} else {
					System.out.println("Syntax error: input device expected after -input");
					System.exit(3);
				}
			} else if (args[i].equals("-mapping")) {
				if (i + 1 < args.length) {
					mapping = args[i+1];
					i++;
				} else {
					System.out.println("Syntax error: mapping file expected after -mapping");
					System.exit(3);
				}
			} else if (args[i].equals("-audio")) {
				if (i + 1 < args.length) {
					audio = args[i+1];
					i++;
				} else {
					System.out.println("Syntax error: audio device expected after -audio");
					System.exit(3);
				}
			} else if (args[i].equals("-pair")) {
				pair = true;
			} else if (args[i].equals("-720")) {
				resolution = 720;
			} else if (args[i].equals("-1080")) {
				resolution = 1080;
			} else if (args[i].equals("-30fps")) {
				refresh = 30;
			} else if (args[i].equals("-60fps")) {
				refresh = 60;
			} else {
				System.out.println("Syntax Error: Unrecognized argument: " + args[i]);
				parse = false;
			}
		}
		
		if (args.length == 0 || !parse) {
			System.out.println("Usage: java -jar limelight-pi.jar [options] host");
			System.out.println("\t-720\t\tUse 1280x720 resolution [default]");
			System.out.println("\t-1080\t\tUse 1920x1080 resolution");
			System.out.println("\t-30fps\t\tUse 30fps");
			System.out.println("\t-60fps\t\tUse 60fps [default]");
			System.out.println("\t-input <device>\tUse <device> as input. Can be used multiple times");
			System.out.println("\t\t\t[default uses all devices in /dev/input]");
			System.out.println("\t-mapping <file>\tUse <file> as gamepad mapping configuration file");
			System.out.println("\t-audio <device>\tUse <device> as ALSA audio output device (default hw:0)");
			System.out.println("\t-pair\t\tPair with host");
			System.out.println();
			System.out.println("Use ctrl-c to exit application");
			System.exit(5);
		} else
			host = args[args.length-1];
		
		StreamConfiguration streamConfig = new StreamConfiguration((resolution/9)*16, resolution, refresh);
		
		Limelight limelight = new Limelight(host);
		if (!pair)
			limelight.startUp(streamConfig, inputs, mapping, audio);
		else
			limelight.pair();
	}
	
	
	public void stop() {
		connectionTerminating = true;
		conn.stop();
	}

	/**
	 * Callback to specify which stage is starting. Used to update UI.
	 * @param stage the Stage that is starting
	 */
	@Override
	public void stageStarting(Stage stage) {
		System.out.println("Starting "+stage.getName());
	}

	/**
	 * Callback that a stage has finished loading.
	 * <br><b>NOTE: Currently unimplemented.</b>
	 * @param stage the Stage that has finished.
	 */
	@Override
	public void stageComplete(Stage stage) {
	}

	/**
	 * Callback that a stage has failed. Used to inform user that an error occurred.
	 * @param stage the Stage that was loading when the error occurred
	 */
	@Override
	public void stageFailed(Stage stage) {
		conn.stop();
		displayError("Connection Error", "Starting " + stage.getName() + " failed");
	}

	/**
	 * Callback that the connection has finished loading and is started.
	 */
	@Override
	public void connectionStarted() {
	}

	/**
	 * Callback that the connection has been terminated for some reason.
	 * <br>This is were the stream shutdown procedure takes place.
	 * @param e the Exception that was thrown- probable cause of termination.
	 */
	@Override
	public void connectionTerminated(Exception e) {
		if (!(e instanceof InterruptedException)) {
			e.printStackTrace();
		}
		if (!connectionTerminating) {
			connectionTerminating = true;

			// Kill the connection to the target
			conn.stop();

			// Spin off a new thread to update the UI since
			// this thread has been interrupted and will terminate
			// shortly
			new Thread(new Runnable() {
				@Override
				public void run() {
					displayError("Connection Terminated", "The connection failed unexpectedly");
				}
			}).start();
		}
	}

	/**
	 * Displays a message to the user in the form of an info dialog.
	 * @param message the message to show the user
	 */
	@Override
	public void displayMessage(String message) {
		System.out.println(message);
	}	

	/**
	 * Displays an error to the user in the form of an error dialog
	 * @param title the title for the dialog frame
	 * @param message the message to show the user
	 */
	public void displayError(String title, String message) {
		System.err.println(title + " " + message);
	}
}

