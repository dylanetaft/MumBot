package JMumbotLib;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;

import javax.net.SocketFactory;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import MumbleProto.Mumble;
import MumbleProto.Mumble.ChannelState;
import MumbleProto.Mumble.UserRemove;
import MumbleProto.Mumble.UserState;

// 2b type, 4b length, rest data

public class MumBotConnection 
{
	public static final short PKT_TYPE_VERSION = 0;
	public static final short PKT_TYPE_AUTH = 2;
	public static final short PKT_TYPE_PING = 3;
	public static final short PKT_TYPE_REJECT = 4;
	public static final short PKT_TYPE_SERVERSYNC = 5;
	public static final short PKT_TYPE_CHANNELREMOVE = 6;
	public static final short PKT_TYPE_CHANNELSTATE = 7;
	public static final short PKT_TYPE_USERREMOVE = 8;
	public static final short PKT_TYPE_USERSTATE = 9;
	public static final short PKT_TYPE_TEXTMESSAGE = 11;
	public static final short PKT_TYPE_PERMISSIONDENIED = 12;
	private Socket s;
	DataInputStream is;
	DataOutputStream os;
	private boolean syncComplete = false; //server sync received
	private static MumBotConnection instance = null;
	public HashMap<Integer,UserState> userStateList = new HashMap<Integer, UserState>(); //used to keep track of users by session
	public HashMap<Integer, ChannelState> channelStateList = new HashMap<Integer,ChannelState>(); //used to keep track of channels by id
	
	private int mySession = 0;
	
	private MumBotListener myListener;
	
	
	private void gotUserRemove(UserRemove remove) {
		int session = remove.getSession();
		userStateList.remove(session);	
		if (myListener != null) myListener.gotUserRemove(remove);
	}
	
	private void gotChannelState(ChannelState state) {
		int channelID = state.getChannelId();
		ChannelState chan = channelStateList.get(channelID);
		if (!channelStateList.containsKey(channelID)) { //new channel
			channelStateList.put(channelID, state);
		}
		else {
			ChannelState oldState = channelStateList.get(channelID);
			
			ChannelState mergedState = oldState.newBuilder()
				//.setName(state.hasName() ? state.getName() : oldState.getName())
				//.setChannelId(state.getChannelId()) //always sent
				//.setDescription(state.hasDescription() ? state.getDescription() : oldState.getDescription())
				.mergeFrom(oldState)
				.mergeFrom(state)
			.build();
			channelStateList.put(channelID,mergedState);
			
		}
		if (myListener != null) myListener.gotChannelState(state);	
		
	}
	private void gotUserState(UserState state) {
		int session = state.getSession();
		if (!userStateList.containsKey(session)) { //new user
			userStateList.put(session, state);
			if (myListener != null) myListener.gotUserState(state, (true && syncComplete));
		}
		else {
			UserState oldState = userStateList.get(session);	
			
			UserState mergedState = UserState.newBuilder()
				//.setName((state.hasName() ? state.getName() : oldState.getName()))
				//.setChannelId((state.hasChannelId() ? state.getChannelId() : oldState.getChannelId()))
				//.setSession(state.getSession()) //always sent
				.mergeFrom(oldState)
				.mergeFrom(state)
			.build();
			userStateList.put(session,mergedState);
			if (myListener != null) myListener.gotUserState(state, false);	
		}
	}
	
	public static MumBotConnection getInstance() {
		if (instance == null) {
			instance = new MumBotConnection();
		}
		return instance;
	}
	
	protected MumBotConnection() {
		
	}
	
	public void setListener(MumBotListener listener)
	{
		myListener = listener;
	}
	public void connect(String hostname, int port, String username, String password)
	{
		try
		{
			
			KeyStore ks = KeyStore.getInstance("JKS");
			InputStream ksfile = new FileInputStream("keystore.jks");
			ks.load(ksfile, "password".toCharArray());
			ksfile.close();
			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(ks,"password".toCharArray());
			TrustManager[] trustAllCerts = new TrustManager[]{
				    new X509TrustManager() {

				        @Override
				        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
				            return null;
				        }

				        @Override
				        public void checkClientTrusted(
				                java.security.cert.X509Certificate[] certs, String authType) {
				        }

				        @Override
				        public void checkServerTrusted(
				                java.security.cert.X509Certificate[] certs, String authType) {
				        }
				    }
				};
			 
			SSLContext sslc = SSLContext.getInstance("TLS");
			sslc.init(kmf.getKeyManagers(), trustAllCerts, new java.security.SecureRandom());
		
			SSLSocketFactory sfac = sslc.getSocketFactory();
			s = (SSLSocket) sfac.createSocket(hostname,port);
			
			//s = SocketFactory.getDefault().createSocket(hostname, port);
			
			is = new DataInputStream(s.getInputStream());
			os = new DataOutputStream(s.getOutputStream());
			sendData(PKT_TYPE_VERSION,createVersionPktData());
			sendData(PKT_TYPE_AUTH,createAuthPktData(username, password));
			sendData(PKT_TYPE_USERSTATE,createDeafMutePktData());
			startKeepAlive();
			
			ByteBuffer curHeader = ByteBuffer.allocate(6);
			ByteBuffer curData = ByteBuffer.allocate(0);
			while (true)
			{
				
				//if (is.available() == 0) continue;
				byte data = is.readByte();
				if (curHeader.hasRemaining()) { //reading header
					curHeader.put(data);
					if (!curHeader.hasRemaining()) //header read
					{
						int packetLength = curHeader.getInt(2); //length
						curData = ByteBuffer.allocate(packetLength);
					}
				}
				else //reading data
				{
					if (curData.hasRemaining())
					{
						curData.put(data);
						if (!curData.hasRemaining()) //finished reading data
						{
							short packetType = curHeader.getShort(0); //type
							switch (packetType)
							{
								case PKT_TYPE_REJECT:
									System.out.println(Mumble.Reject.parseFrom(curData.array()).getReason());
									break;
								case PKT_TYPE_CHANNELSTATE:
									ChannelState channelstate = ChannelState.parseFrom(curData.array());
									gotChannelState(channelstate);
									break;
									
								case PKT_TYPE_TEXTMESSAGE:
									if (myListener != null) myListener.gotTextMessage(Mumble.TextMessage.parseFrom(curData.array()));
									break;
								case PKT_TYPE_SERVERSYNC:
									System.out.println("Register");
									Mumble.ServerSync myServerSync = Mumble.ServerSync.parseFrom(curData.array());
									mySession = myServerSync.getSession();
									if (myListener != null) myListener.gotServerSync(myServerSync);
									sendData(PKT_TYPE_USERSTATE,createRegisterUserPktData());
									syncComplete = true;
									break;
								case PKT_TYPE_USERSTATE:
									UserState userstate = UserState.parseFrom(curData.array());
									gotUserState(userstate);
									break;
								case PKT_TYPE_USERREMOVE:
									UserRemove userRemove = Mumble.UserRemove.parseFrom(curData.array());
									gotUserRemove(userRemove);
								case PKT_TYPE_PERMISSIONDENIED:
									System.out.println("Permission denied:" + Mumble.PermissionDenied.parseFrom(curData.array()).getType().toString());
									break;
							}
							curHeader.clear();
							curData.clear();
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			e.printStackTrace();
			System.err.println(e.getCause().getMessage());
			System.err.println(e.getMessage());
		}
		
	}
	private void startKeepAlive()
	{
		TimerTask task = new TimerTask() {

			@Override
			public void run() {
				// TODO Auto-generated method stub
				try
				{
					sendData(PKT_TYPE_PING,createPingPktData());
				}
				catch (Exception e)
				{
					
				}
			}
			
		};
		Timer timer = new Timer();
		timer.scheduleAtFixedRate(task, 0, 15000);
	}
	
	public void sendTextMessage(int channelID, String message)  {
		sendData(PKT_TYPE_TEXTMESSAGE,createTextMessagePktData(channelID, message));
	}
	
	public void joinChannel(int id)
	{
			sendData(PKT_TYPE_USERSTATE,createJoinChannelPktData(id));
	}
	
	public synchronized void sendData(short ptype, byte[] data) 
	{
		ByteBuffer packet = ByteBuffer.allocate(2 + 4 + data.length); //2 bytes for type, 4 bytes for length, data.length
		packet.putShort(ptype);
		packet.putInt(data.length);
		packet.put(data);
		try
		{
			os.write(packet.array());
		}
		catch (Exception e)
		{
			e.printStackTrace();
			System.err.println(e.getCause().getMessage());
			System.err.println(e.getMessage());
		}
	}
	
	private byte[] createTextMessagePktData(int channelID, String message) {
		return Mumble.TextMessage.newBuilder()
				.addChannelId(channelID)
				.setMessage(message)
				.build().toByteArray();
	}
	private byte[] createVersionPktData() {
		return Mumble.Version.newBuilder()
		.setVersion(66048)
		.setRelease("1.2.4")
		.build().toByteArray();
	}
	private byte[] createAuthPktData(String username, String password) {
		return Mumble.Authenticate.newBuilder()
				.setUsername(username)
				.setPassword(password)
				.build().toByteArray();
	}
	private byte[] createDeafMutePktData() {
		return Mumble.UserState.newBuilder()
				.setSelfDeaf(true)
				.setSelfMute(true)
				.build().toByteArray();
	}
	private byte[] createPingPktData() {
		return Mumble.UserState.newBuilder()
		.build().toByteArray();
	}
	private byte[] createJoinChannelPktData(int id) {
		return Mumble.UserState.newBuilder()
				.setChannelId(id)
				.build().toByteArray();
			
	}
	private byte[] createRegisterUserPktData() {
		return Mumble.UserState.newBuilder()
				.setUserId(0)
				.setSession(mySession)
				.build().toByteArray();
				
		
	}
	
}
