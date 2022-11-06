package su.linka.pictures.components;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import su.linka.pictures.Callback;
import su.linka.pictures.Card;
import su.linka.pictures.R;
import su.linka.pictures.Set;
import su.linka.pictures.TTS;
import su.linka.pictures.Utils;

public class EditCardDialog extends Dialog {
    private static final int[] IDS = new int[]{R.id.standard_card_radio, R.id.space_card_radio, R.id.empty_card_radio};
    private static final int PICK_IMAGE = 0;
    private final MediaPlayer mp;
    private Card card;
    private Set set;
    private RadioGroup cardTypeRadio;
    private EditText cardTitleEditText;
    private View imageEditBlock;
    private ImageView imageView;
    private View imageEditButtons;
    private Button playAudioButton;
    private RecordButton recordButton;
    private File currentAudio;
    private Button generateAudioButton;
    private final TTS tts;
    private Button chooseImageButton;
    private Bitmap currentBitmap;
    private Button generateImageButton;
    private Callback<Card> callback;

    public EditCardDialog(@NonNull Context context) {
        super(context);
        setCancelable(true);
        tts = new TTS(context);
        mp = new MediaPlayer();
        setTitle(R.string.create_card);

        setContentView(R.layout.edit_card_dialog);

    }

    public void show(Set set, Card card) {
        this.set = set;
        this.card = card;
        if (card == null) {
            this.card = new Card(0, 0);
        }
        prepareView();
        show();
    }

    private void prepareView() {
        prepareDialogButtons();
        prepareInputs();
        insertCardData();
        setUpListeners();
    }

    private void setUpListeners() {
        recordButton.setOnRecordListener(new Callback<File>() {
            @Override
            public void onDone(File result) {
                currentAudio = result;
                playAudioButton.setEnabled(true);
            }

            @Override
            public void onFail(Exception error) {
                playAudioButton.setEnabled(false);
            }
        });
        generateAudioButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generateAudio();
            }
        });
        chooseImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseImage();
            }
        });
        generateImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generateImage();
            }
        });
        playAudioButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playAudio();
            }
        });
    }

    private void playAudio() {
        if (currentAudio == null) return;
        try {
            mp.reset();

            mp.setDataSource(currentAudio.getAbsolutePath());
            mp.prepare();
            mp.start();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(getContext(), R.string.play_audio_error, Toast.LENGTH_LONG).show();
        }

    }

    private void generateImage() {
        InputDialog
                .showDialog(getContext(), R.string.generate_image, new Callback<String>() {
                    @Override
                    public void onDone(String result) {
                        currentBitmap = Utils.textAsBitmap(result);
                        imageView.setImageBitmap(currentBitmap);
                    }

                    @Override
                    public void onFail(Exception error) {

                    }
                });
    }

    private void chooseImage() {
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        (Utils.unwrap(getContext())).startActivityForResult(Intent.createChooser(intent, getContext().getString(R.string.choose_image)), PICK_IMAGE);

    }

    private void generateAudio() {
        InputDialog
                .showDialog(getContext(), R.string.generate_audio, new Callback<String>() {
                    @Override
                    public void onDone(String result) {
                        try {
                            currentAudio = tts.speakToBuffer(result);
                        } catch (Exception e) {
                            Toast.makeText(getContext(), R.string.generate_audio_error, Toast.LENGTH_LONG).show();
                            return;
                        }

                        playAudioButton.setEnabled(true);

                    }

                    @Override
                    public void onFail(Exception error) {

                    }
                });

    }

    private void insertCardData() {

        cardTypeRadio.check(IDS[card.cardType]);
        if (card.cardType == 0) {
            if (card.title != null && !card.title.equals("")) {
                cardTitleEditText.setText(card.title);
            } else {
                cardTitleEditText.setText("");
            }
            if (card.imagePath != null) {
                imageView.setImageBitmap(set.getBitmap(card.imagePath));
            } else {
                imageView.setImageBitmap(null);
            }
            if (card.audioPath != null) {
                currentAudio = set.getAudioFile(card.audioPath);
                playAudioButton.setEnabled(true);
            } else {
                currentAudio = null;
                playAudioButton.setEnabled(false);
            }
        }
    }

    private void prepareInputs() {
        cardTypeRadio = findViewById(R.id.card_type_radiogroup);
        cardTitleEditText = findViewById(R.id.card_title_edittext);
        imageEditBlock = findViewById(R.id.image_edit_block);
        imageView = findViewById(R.id.image);
        imageEditButtons = findViewById(R.id.image_edit_buttons);
        playAudioButton = findViewById(R.id.play_audio_button);

        recordButton = findViewById(R.id.record_audio_button);
        generateAudioButton = findViewById(R.id.generate_audio_button);
        chooseImageButton = findViewById(R.id.choose_image_button);
        generateImageButton = findViewById(R.id.generate_image_button);
    }

    private void prepareDialogButtons() {
        findViewById(R.id.positiveBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean result = save();
                if(result) {
                    dismiss();
                }
            }
        });
        findViewById(R.id.cancel_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }

    private boolean save() {
        boolean res = validate();
        if(!res) {
            Toast.makeText(getContext(), R.string.card_fields_doesnt, Toast.LENGTH_LONG).show();
            return false;
        }
        int type = getTypeFromRadio();

        card.cardType=type;
        if(type==0){
            card.title = cardTitleEditText.getText().toString();
            card.imagePath = set.saveBitmap(currentBitmap).getName();
            try {
                card.audioPath = set.copyAudioFile(currentAudio).getName();
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

        } else {
            card.audioPath=null;
            card.imagePath=null;
        }
        callback.onDone(card);
    return true;
    }

    private int getTypeFromRadio() {
        int type = 0;
        int id = cardTypeRadio.getCheckedRadioButtonId();
        for (int i = 0; i < IDS.length; i++) {
            if(id==IDS[i]){
                type=i;
                break;
            }
        }
        return type;
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_IMAGE && resultCode == Activity.RESULT_OK && data != null) {

            Uri selectedImage = data.getData();
            Bitmap bitmap = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContext().getContentResolver(), selectedImage));
                } else {
                    bitmap = MediaStore.Images.Media.getBitmap(getContext().getContentResolver(), selectedImage);
                }
            } catch (IOException e) {
                Toast.makeText(getContext(), R.string.image_open_error, Toast.LENGTH_LONG).show();
                e.printStackTrace();
                return;
            }
            currentBitmap = bitmap;
            imageView.setImageBitmap(bitmap);
        }
    }

    public boolean validate() {
        switch (card.cardType) {
            case 0:
                String text = cardTitleEditText.getText().toString();
                if (text.length() == 0) return false;
                if (currentBitmap == null) return false;
                if (currentAudio == null) return false;
                return true;
            case 1:
            case 2:
                return true;
            default:
                return false;
        }
    }

    public void setCallback(Callback<Card> callback) {
        this.callback = callback;
    }
}