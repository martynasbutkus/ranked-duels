package com.rankedduels;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;
import java.awt.event.KeyEvent;

@ConfigGroup("rankedduels")
public interface RankedDuelsConfig extends Config
{
    @ConfigItem(
        keyName = "apiBaseUrl",
        name = "API base URL",
        description = "Internal - the Ranked Duels server address.",
        position = 1,
        hidden = true
    )
    default String apiBaseUrl()
    {
        return "https://rankedduel.com/wp-json/rankedduels/v1";
    }

    @ConfigItem(
        keyName = "linkCode",
        name = "Account link code",
        description = "Paste the one-time code from rankedduel.com -> My Account, then log in. That's all the setup you need.",
        position = 1,
        secret = true
    )
    default String linkCode()
    {
        return "";
    }

    @ConfigItem(
        keyName = "acceptKey",
        name = "Accept duel hotkey",
        description = "Press to accept an incoming ranked duel challenge.",
        position = 3
    )
    default Keybind acceptKey()
    {
        return new Keybind(KeyEvent.VK_Y, 0);
    }

    @ConfigItem(
        keyName = "declineKey",
        name = "Decline duel hotkey",
        description = "Press to decline an incoming challenge, or cancel your own pending challenge/duel.",
        position = 4
    )
    default Keybind declineKey()
    {
        return new Keybind(KeyEvent.VK_N, 0);
    }

    @ConfigItem(
        keyName = "apiToken",
        name = "API token",
        description = "Internal - filled automatically after linking.",
        position = 2,
        secret = true,
        hidden = true
    )
    default String apiToken()
    {
        return "";
    }
}
