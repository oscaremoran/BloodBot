# 2026-11-10: Frustration on Frozen Forest

I ran the map for a while now in the past few days and haven't really gotten anywhere. The bot isn't getting many turrets down to shoot the enemy units. I went back and looked at the earlier levels, and it's actually pretty tough. The first few levels have a steep difficulty curve. Here are some of the things that make it tough:
 * The bot can only see two things: the full map, which is like a picture of the whole level squeezed down into a small picture, and the zoomed map, which shows a small area right around the player's robot. For it to beat the level, it must place turrets in a strategic way to funnel enemies down the right paths down into a chokepoint, which has a really low chance of happening.
 * There are a LOT of actions you can take in Mindustry. You can place a block, of which there are dozens, maybe hundreds, multiplied by the number of available spots to place it at, which gives you thousands of options. You can shoot an enemy, in any location, which adds a hundred options or so. You can command a unit, you can fly around, you can configure a block (choose what you want a block to do), and some more I can't remember. This adds up to thousands and thousands of options. If you picked an action at random 1,000,000 times, you wouldn't get remotely close to a single turret to work.
 * You need lots of turrets, probably at least 15 to get a halfway decent score...

 All of these factors combined make it really tough for the AI to win the map. If you do the actual math on how hard it is to build 15 turrets on the enemy path:

DISCLAIMER: I AM NOT RESPONSIBLE FOR PEOPLE'S REACTIONS AFTER LOOKING AT THE FOLLOWING EQUATION.

*Crazy Math*

About 200 tiles on the enemy path * 1/243^15 (the 243 is for the number of blocks in the game) for turrets to be placed there (by the way that's one over two hundred forty-three to the *fifteenth*.) * another 1/243^15 for conveyors to be connected to the turrets * 1/243^10 for drills to be set up and connected. That's pretty tough. If you actually write it out:

200 * 1/243^15 * 1/243^15 * 1/243^10 ≈ 1 / 1,​328,​069,​944,​379,​373,​846,​693,​906,​610,​178,​898,​134,​146,​162,​263,​266,​972,​479,​872,​874,​808,​695,​462,​454,​506,​510,​914,​971,​923,​495,​220,​006. Yikes. If you want to see the decimal, I've put the first million digits here. No, it's not all zeroes. 

[The first million digits (Be warned)](images/one-million-digits-of-fraction.txt)

*Back to Blog*

This concept is called a ***combinatorial explosion***. It means that a few options multiplied together give you lots and lots of different possible outcomes. I had a bit of fun with this one, but it is kind of ridiculous to see how many options there are. If you picked some other options, you might end up with a bunch of random blocks, a perfect line of walls, a picture using sorters (they can display color)... there are countless (almost) options. Chess programs for bots (Deep Blue) can't just test all the combinations because of the combinatorial explosion. There are so many that you have to use some learning program like this instead. Thanks for reading!


