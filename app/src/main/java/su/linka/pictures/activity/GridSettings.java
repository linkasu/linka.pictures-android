package su.linka.pictures.activity;

public class GridSettings {
    boolean isOutput = true;
    boolean isPagesButtons = true;

    public GridSettings(){}
    public GridSettings(boolean isOutput, boolean isPagesButtons) {
        this.isOutput = isOutput;

        this.isPagesButtons = isPagesButtons;
    }

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

    int toInt(){

        int i = 0;
        if(isOutput) i+=1;
        if(isPagesButtons) i+=2;
        return i;

    }

    public static GridSettings fromInt(int value){
        GridSettings settings = new GridSettings(false, false);

        if(value==1||value==3){
            settings.isOutput=true;
        }
        if(value==2||value==3){
            settings.isPagesButtons = true;
        }

            return  settings;
    }
}
