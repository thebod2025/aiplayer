package com.aiplayer.config;

import java.util.Locale;

public class RemoteBotProfile {
   public volatile String name = "Bot";
   public volatile boolean enabled = true;
   public volatile String host = "127.0.0.1";
   public volatile int port = 25565;
   public volatile String username = "";
   public volatile String version = "";
   public volatile String personality = "";
   public volatile boolean autoReply = true;
   public volatile boolean autoBrain = true;
   public volatile boolean pvp = false;
   public volatile String ownerName = "";
   public volatile boolean listenAll = false;
   public volatile String password = "";
   public volatile String authMode = "auto";
   public volatile String mcAuth = "offline";
   public volatile boolean followMe = false;

   public RemoteBotProfile() {
   }

   public RemoteBotProfile(String var1) {
      this.name = var1;
   }

   public String displayName() {
      String var1 = this.name == null ? "" : this.name.trim();
      return var1.isEmpty() ? "Bot" : var1;
   }

   public String safeName() {
      String var1 = this.displayName();
      StringBuilder var2 = new StringBuilder(var1.length());

      for (int var3 = 0; var3 < var1.length() && var2.length() < 32; var3++) {
         char var4 = var1.charAt(var3);
         if (var4 >= ' ' && var4 != 127) {
            var2.append(var4);
         }
      }

      return var2.toString();
   }

   public void normalize() {
      if (this.name == null || this.name.isBlank()) {
         this.name = "Bot";
      }

      if (this.host == null || this.host.isBlank()) {
         this.host = "127.0.0.1";
      }

      if (this.port < 1 || this.port > 65535) {
         this.port = 25565;
      }

      if (this.password == null) {
         this.password = "";
      }

      String var1 = this.authMode == null ? "" : this.authMode.trim().toLowerCase(Locale.ROOT);
      if (!var1.equals("login") && !var1.equals("register")) {
         var1 = "auto";
      }

      this.authMode = var1;
      this.mcAuth = "microsoft".equalsIgnoreCase(this.mcAuth == null ? "" : this.mcAuth.trim()) ? "microsoft" : "offline";
   }
}
