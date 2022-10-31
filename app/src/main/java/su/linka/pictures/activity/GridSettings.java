package su.linka.pictures.activity;

public class GridSettings {
    boolean isOutput = true;
    boolean isPagesButtons = true;

    public boolean isPagesButtons() {
        return isPagesButtons;
    }

    public void setPagesButtons(boolean pagesButtons) {
        isPagesButtons = pagesButtons;
    }

    public boolean getIsOutput(){
        return isOutput;
    }

    public void setIsOutput(boolean output) {
        isOutput = output;
    }

}
