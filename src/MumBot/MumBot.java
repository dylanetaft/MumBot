package MumBot;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Properties;
import java.util.Random;

import JMumbotLib.*;
import MumbleProto.Mumble;
import MumbleProto.Mumble.ChannelState;
import MumbleProto.Mumble.ServerSync;
import MumbleProto.Mumble.TextMessage;
import MumbleProto.Mumble.UserRemove;
import MumbleProto.Mumble.UserState;


public class MumBot implements MumBotListener {


	private HashMap<Integer, Long> userPunishTime = new HashMap<Integer, Long>();
	private int punishChannelID = 0;
	private int mySession = -1;
	private Properties botSettings = new Properties();
	private Random random = new Random();
	
	public static void main(String[] args) 
	{
		MumBot bot = new MumBot();
	}
	
	public MumBot()
	{
		loadSettings();
		MumBotConnection.getInstance().setListener(this);
		MumBotConnection.getInstance().connect(botSettings.getProperty("server"),Integer.parseInt(botSettings.getProperty("port")), botSettings.getProperty("username"),"");	
		
	}

	@Override
	public void gotChannelState(ChannelState state) {
		if (state.getName().equals("Penalty Box"))
		{
			punishChannelID = state.getChannelId();
		}
		
	}

	@Override
	public void gotTextMessage(TextMessage message) {
		if (mySession == -1) return;
		
		int myChannelID = MumBotConnection.getInstance().userStateList.get(mySession).getChannelId(); //channel user spoke in
		String[] words = message.getMessage().split("\\s+");
		if (words.length < 1) return;
		
	

		if (words[0].equals("!punish") && message.getSessionCount() == 0 && words.length > 1) {
			for (Integer userSessionID:MumBotConnection.getInstance().userStateList.keySet()) {
				if (MumBotConnection.getInstance().userStateList.get(userSessionID).getName().equals(words[1])) {
					MumBotConnection.getInstance().sendTextMessage(myChannelID,MumBotConnection.getInstance().userStateList.get(userSessionID).getName() + " has been banished for 30 seconds. :getout:");
					userPunishTime.put(userSessionID, System.currentTimeMillis());
					punishUser(userSessionID);
				}
			}
		}
		else if (words[0].equals("!stopic") && words.length > 1 ) {
			String newname = message.getMessage().substring(words[0].length() + 1);
		
			byte[] data = ChannelState.newBuilder()
					.setChannelId(myChannelID)
					.setName(newname)
					.build().toByteArray();
			MumBotConnection.getInstance().sendData(MumBotConnection.PKT_TYPE_CHANNELSTATE,data);
		}
		else if (words[0].equals("!smotd") && message.getSessionCount() == 0 && words.length > 1) {
			String motd = message.getMessage().substring(words[0].length() + 1);
			botSettings.setProperty("motd", motd);
			saveSettings();
		}
		
		else if (words[0].equals("!help") && message.getSessionCount() == 0) {
			MumBotConnection.getInstance().sendTextMessage(myChannelID,"!punish nickname - Kicks the user out and to the Penalty Box where he or she shall stay for 30 seconds or until I crash");
			MumBotConnection.getInstance().sendTextMessage(myChannelID,"!smotd your words here - Set your own annoying message users will get upon login");
			MumBotConnection.getInstance().sendTextMessage(myChannelID,"!stopic your new name here - renames the channel the bot is in");
					
		}
		
				

	}
	
	private void saveSettings() {
		
		try {
			FileOutputStream fos = new FileOutputStream("./settings.properties");
			botSettings.store(fos, "Bot Settings");
			fos.close();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private void loadSettings() {
		try {
			FileInputStream fis = new FileInputStream("./settings.properties");
			botSettings.load(fis);
			fis.close();
		} catch (Exception e) {
		}
		
	
	}
	private void punishUser(int sessionid) {
		System.out.println("Punishing " + MumBotConnection.getInstance().userStateList.get(sessionid).getName());
		byte[] data = UserState.newBuilder()
				.setSession(sessionid)
				.setChannelId(punishChannelID)
				.build().toByteArray();
		MumBotConnection.getInstance().sendData(MumBotConnection.PKT_TYPE_USERSTATE,data);	
	}
	
	@Override
	public void gotUserState(UserState state, boolean newuser) {
		
		if (!MumBotConnection.getInstance().userStateList.containsKey(mySession)) return; //we don't have our own information yet
		UserState fullUserState = MumBotConnection.getInstance().userStateList.get(state.getSession());
		int myChannelID = MumBotConnection.getInstance().userStateList.get(mySession).getChannelId();
		Long punishTime = userPunishTime.get(state.getSession());
		
		if (punishTime != null && state.hasChannelId()) { //channel id changed and user is punished
			long destTime = punishTime + 30000;
			long curTime = System.currentTimeMillis();
			if (System.currentTimeMillis() < destTime) //rekick user
			{
				int session = state.getSession();
				MumBotConnection.getInstance().sendTextMessage(myChannelID,fullUserState.getName() + " is still banished for " + (float)(destTime - curTime)/1000.00  + " more seconds.");
				punishUser(session);;
				
			}
		}
		if (newuser) { //send motd
			TextMessage motdmsg = TextMessage.newBuilder()
					.setMessage("-MOTD- " + botSettings.getProperty("motd") + " -MOTD-")
					.addSession(state.getSession())
					.build();
			
			MumBotConnection.getInstance().sendData(MumBotConnection.PKT_TYPE_TEXTMESSAGE, motdmsg.toByteArray());
		}
		
	}

	@Override
	public void gotUserRemove(UserRemove remove) {
		int session = remove.getSession();
		
	}

	@Override
	public void gotServerSync(ServerSync sync) {
		mySession = sync.getSession();
		// TODO Auto-generated method stub
		
	}

}
