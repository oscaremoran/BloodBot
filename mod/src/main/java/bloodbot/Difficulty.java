package bloodbot;

/**
 * How hard the bot plays. Each level is a brain saved at a different point in training, played with a
 * different amount of randomness: higher temperature = more random, sloppier choices.
 */
public enum Difficulty{
    easy("Easy", "easy.bin", 1.6f),
    normal("Normal", "normal.bin", 1.0f),
    hard("Hard", "hard.bin", 0.6f),
    insane("Insane", "insane.bin", 0.25f);

    public final String title, brainFile;
    public final float temperature;

    Difficulty(String title, String brainFile, float temperature){
        this.title = title;
        this.brainFile = brainFile;
        this.temperature = temperature;
    }
}
