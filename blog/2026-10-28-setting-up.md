# 2026-10-28: Setting up

I wanted to make a bot to beat the Mindustry map Bloodshed Bastion because I'm interested in AI. I had just recently watched the documentary The Thinking Game, about DeepMind's initial AI training and the part about how they trained their StarCraft 2 bot was really cool. I thought of making a bot to play Mindustry, a game that I play. So, I tried just starting, and the bot did pretty well. Then, I realized that it wasn't learning, so I changed it to the learn-by-itself approach. It did absolutely awful! It was shockingly bad. The bots kept dying at around wave 2-3 and only hand-shooting the enemies!

## Server

I ran the game by opening a server that could play games at about 35x real time, which I initially thought was amazing, but turned out to be pretty slow. That meant that each game took about 15 (if it died right away) to 40 (if it almost won) seconds per game, which ended up taking FOREVER. Then, I realized that I could just run it on three servers, which tripled the speed of it, effectively meaning that each match took 5 to 15 seconds, which was great. This seems to be the best that this computer can handle, but maybe at some point I'll try out four servers.

## Map survey: how the built-in maps behave with no player

I checked most of the pre-built maps, and all of them were meant for good players. So, I had an idea. I remembered from when I made a Mindustry mod that the campaign map files 

## The ladder

| Step | Map | Kind | Win condition |
|---|---|---|---|
| 1 | Ground Zero | waves | survive 10 waves |
| 2 | Frozen Forest | waves | survive 15 waves |
| 3 | Cratered Battleground | waves | survive 20 waves |
| 4 | Fungal Pass | attack | destroy the enemy cores |
| 5 | USS Iowa | attack | destroy the enemy cores |
| 6 | Space Gardens | 1v1v1v1 self-play | last bot standing |
| 7 | Bloodshed Bastion | attack | destroy the enemy cores |

Each map is played at 30% → 55% → 80% → 100% enemy strength. Clearing half of the last 10 games (a win with the core at 50% or more) moves the bot up a level.

> ✍️ *Why this order?*
