"GameInfo"
{
	game 		"citadel"
	title 		"Citadel"
	type		multiplayer_only
	nomodels 1
	nohimodel 1
	nocrosshair 0
	hidden_maps
	{
		"test_speakers"			1
		"test_hardware"			1
	}
	nodegraph 0
	perfwizard 0
	tonemapping 0
	GameData	"citadel.fgd"

	PGIVersion "48C04ECAFC6448F8D3F41F0AA3A15D065501751CF45FBE32DF6CB951BC4F7F6E"

	Localize
	{
		DuplicateTokensAssert	1
	}

	FileSystem
	{	
		//
		// The code that loads this file automatically does a few things here:
		//
		// 1. For each "Game" search path, it adds a "GameBin" path, in <dir>\bin
		// 2. For each "Game" search path, it adds another "Game" path in front of it with _<language> at the end.
		//    For example: c:\hl2\cstrike on a french machine would get a c:\hl2\cstrike_french path added to it.
		// 3. If no "Mod" key, for the first "Game" search path, it adds a search path called "MOD".
		// 4. If no "Write" key, for the first "Game" search path, it adds a search path called "DEFAULT_WRITE_PATH".
		//

		//
		// Search paths are relative to the exe directory\..\
		//
	
// Deadlock Mod Manager - Start

		SearchPaths
        {  
            Game_UILanguage     citadel_*LANGUAGE*
            Game_LowViolence    citadel_lv
            Game                citadel/deadworks_mods
            addonroot           citadel/deadworks_addons
            Game                citadel/dmm_localization_default
            Game                citadel/addons/server_42
            Game                citadel/addons/profile_1726000000000_ab12cd_ranked
            Game                citadel/addons2/profile_1726000000000_ab12cd_ranked
            Mod                 citadel
            Write               citadel          
            Game                citadel
            Mod                 core
            Write               core
            Game                core        
        }
// Deadlock Mod Manager - End

		"UserSettingsPathID"		"USRLOCAL"
		"LegacyUserSettingsPathID"	"MOD"
	}
	ConVars
	{	 
		// ==== Deadlock Mod Manager · Performance BEGIN (config=balanced rev=0123456789ab) ====
		// Balanced: community preset [dmm-perf]
		"r_citadel_new_key"		"1"
		// ==== Deadlock Mod Manager · Performance END ====
		"fps_max"		"0" // dmm-perf was "400"
		"rate"
		{
			"min"		"98304"
			"max"		"1000000" // grimoire-perf was "786432"
		}
	}
}
