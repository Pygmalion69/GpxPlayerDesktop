#pragma once

#include <QMainWindow>
#include <QFileDialog>
#include <QFile>
#include <QXmlStreamReader>
#include <QWebEngineView>
#include <QSlider>
#include <QLabel>
#include <QTextEdit>
#include <QSettings>
#include <QCoreApplication>
#include <QElapsedTimer>



class MainWindow : public QMainWindow
{
    Q_OBJECT

public:
    MainWindow(QWidget* parent = nullptr);

    
private slots:
    void onLoadFinished(bool);
    void openGpxFile();
    void playGpxFile();
    void stopPlayGpxFile();
    void onSpeedChanged(int value);
    void setAdbPath();
   

private:
    QWebEngineView* mapView;
    QList<QPair<double, double>> trackPoints;
    QList<QPair<double, double>> refinedTrackPoints;
    QTimer* timer;
    int currentIndex;
    double speedKmh;
    bool playing = false;
    bool firstIntentSent = false;
    QSlider* speedSlider;
    QLabel* speedLabel;
    QTextEdit* outputTextEdit;
    QSettings* settings;
    QElapsedTimer* intentElapsedTimer;

    void addTrackPoint(QXmlStreamReader&);
    void drawTrack();
    void refineTrack();
    void sendIntent(double, double);
    void startAndroidApp();
    void runAdbCommand(QString);
    double calculateDistance(double, double, double, double);
};