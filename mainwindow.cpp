#include "mainwindow.h"
#include <QMenuBar>
#include <QMessageBox>
#include <QApplication>
#include <QTimer>
#include <qmath.h>
#include <QElapsedTimer>
#include <QVboxLayout>
#include <QSettings>
#include <QTextEdit>
#include <QProcess>

MainWindow::MainWindow(QWidget* parent)
	: QMainWindow(parent), speedKmh(60)
{
	settings = new QSettings(tr("nitri.org"), tr("GpxPlayer"));
	// Create the web-based map view using Leaflet
	mapView = new QWebEngineView(this);
	connect(mapView, &QWebEngineView::loadFinished, this, &MainWindow::onLoadFinished);
	mapView->load(QUrl::fromLocalFile(QApplication::applicationDirPath() + "/map.html"));

	// Create the slider widget
	speedSlider = new QSlider(Qt::Horizontal, this);
	speedSlider->setMinimum(0);
	speedSlider->setMaximum(130);
	speedSlider->setValue(speedKmh);
	connect(speedSlider, &QSlider::valueChanged, this, &MainWindow::onSpeedChanged);

	// Create the label widget to display the speed value
	speedLabel = new QLabel(QString::number(speedKmh) + " km/h", this);
	speedLabel->setAlignment(Qt::AlignCenter);
	speedLabel->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);

	// Create a text edit widget to display the output of the adb command
	outputTextEdit = new QTextEdit(this);
	outputTextEdit->setReadOnly(true);
	QFontMetrics m(outputTextEdit->font());
	int RowHeight = m.lineSpacing();
	outputTextEdit->setFixedHeight(5 * RowHeight);;
	outputTextEdit->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding);
	outputTextEdit->setVerticalScrollBarPolicy(Qt::ScrollBarAsNeeded);


	// Create a vertical layout to add the slider and label widgets
	QVBoxLayout* layout = new QVBoxLayout();
	layout->addWidget(speedSlider);
	layout->addWidget(speedLabel);
	layout->addWidget(mapView);
	layout->addWidget(outputTextEdit);

	// Create a central widget and set the layout to it
	QWidget* centralWidget = new QWidget(this);
	centralWidget->setLayout(layout);
	setCentralWidget(centralWidget);

	// Create the "GPX" button and connect it to the "openGpxFile" slot
	QAction* openGpxAction = new QAction(tr("GPX"), this);
	connect(openGpxAction, &QAction::triggered, this, &MainWindow::openGpxFile);
	menuBar()->addAction(openGpxAction);

	// Create the "Play" button and connect it to the "playGpxFile" slot
	QAction* playGpxAction = new QAction(tr("Play"), this);
	connect(playGpxAction, &QAction::triggered, this, &MainWindow::playGpxFile);
	menuBar()->addAction(playGpxAction);

	QAction* stopGpxAction = new QAction(tr("Stop"), this);
	connect(stopGpxAction, &QAction::triggered, this, &MainWindow::stopPlayGpxFile);
	menuBar()->addAction(stopGpxAction);

	// Create the "ADB" button and connect it to the "setAdbPath" slot
	QAction* setAdbPathAction = new QAction(tr("ADB"), this);
	connect(setAdbPathAction, &QAction::triggered, this, &MainWindow::setAdbPath);
	menuBar()->addAction(setAdbPathAction);

}

void MainWindow::onLoadFinished(bool ok)
{
	if (ok) {

		// Execute some debugging code in the web page and log the output to the console
		mapView->page()->runJavaScript("console.log('Web page loaded successfully')");
		mapView->page()->runJavaScript("console.log(document.getElementById('map'))");

		startAndroidApp();
	}
}

void MainWindow::openGpxFile()
{
	// Use the file picker to get the path to the GPX file
	QString fileName = QFileDialog::getOpenFileName(this, tr("Open GPX File"), "", tr("GPX Files (*.gpx)"));
	if (fileName.isEmpty()) {
		return;
	}

	// Parse the GPX file and extract the coordinates of the track
	QFile file(fileName);
	if (!file.open(QIODevice::ReadOnly)) {
		QMessageBox::warning(this, tr("Error"), tr("Could not open file: ") + file.errorString());
		return;
	}

	trackPoints.clear();
	QXmlStreamReader xml(&file);
	while (!xml.atEnd()) {
		QXmlStreamReader::TokenType nextToken = xml.readNext();
		if (nextToken == QXmlStreamReader::StartElement)
		{
			if (xml.name().toString() == "trkpt") {
				addTrackPoint(xml);
			}
		}

	}

	if (xml.hasError()) {
		QMessageBox::warning(this, tr("Error"), tr("XML parsing error: ") + xml.errorString());
	}

	drawTrack();
	
}

void MainWindow::addTrackPoint(QXmlStreamReader& xml)
{
	qDebug() << "Append: " << xml.name();
	QString latStr = xml.attributes().value("lat").toString();
	QString lonStr = xml.attributes().value("lon").toString();
	double lat = latStr.toDouble();
	double lon = lonStr.toDouble();
	trackPoints.append(qMakePair(lat, lon));
}

void MainWindow::drawTrack() {
	// Create a JavaScript array of coordinate pairs for the polyline
	QString js = "var latlngs = [";
	for (int i = 0; i < trackPoints.size(); i++) {
		QString lat = QString::number(trackPoints[i].first, 'f', 6);
		QString lon = QString::number(trackPoints[i].second, 'f', 6);
		js += QString("[%1, %2],").arg(lat).arg(lon);
	}
	// Remove the trailing comma and close the array
	js.chop(1);
	js += "];";
	
	// Create or update the polyline on the map using the array of coordinate pairs
	js += "if (typeof polyline === 'undefined') {";
	js += "  polyline = L.polyline(latlngs);";
	js += "  polyline.addTo(map);";
	js += "} else {";
	js += "  polyline.setLatLngs(latlngs);";
	js += "}";
	js += "map.fitBounds(polyline.getBounds());";


	qDebug() << "JS command:" << js;

	mapView->page()->runJavaScript(js);
}

void MainWindow::refineTrack() {
	const double interval = 10;
	refinedTrackPoints.clear();
	if (trackPoints.size() > 0) {
		QPair<double, double> prevTrackPoint = trackPoints[0];
		refinedTrackPoints.append(prevTrackPoint);
		qDebug("First track point added: %.6f, %.6f", prevTrackPoint.first, prevTrackPoint.second);
		for (int i = 1; i < trackPoints.count(); ++i) {
			QPair<double, double> trackPoint = trackPoints[i];
			long double distance = calculateDistance(prevTrackPoint.first, prevTrackPoint.second, trackPoint.first, trackPoint.second);		
			if (distance > interval) {
				double deltaLat = trackPoint.first - prevTrackPoint.first;
				double deltaLon = trackPoint.second - prevTrackPoint.second;
				double fraction = interval / distance;
				qDebug("deltaLat: % .6f, deltaLon: % .6f, fraction: % .6f, fraction * deltaLon: % .6f", deltaLat, deltaLon, fraction, fraction * deltaLon);
				int numNewPoints = (int)(distance / interval);
				for (int j = 1; j <= numNewPoints; ++j) {
					double lat = prevTrackPoint.first + (j * fraction * deltaLat);
					double lon = prevTrackPoint.second + (j * fraction * deltaLon);
					refinedTrackPoints.append(qMakePair(lat, lon));
					qDebug("New point added : % .6f, % .6f", lat, lon);
				}
			}
			refinedTrackPoints.append(trackPoint);
			qDebug("Track point added : % .6f, % .6f", trackPoint.first, trackPoint.second);
			prevTrackPoint = trackPoint;
		}
	}

}

// Define the "playGpxFile" function to loop through the list of track points
void MainWindow::playGpxFile()
{
	// Check if there are any track points
	if (trackPoints.isEmpty()) {
		QMessageBox::warning(this, tr("Error"), tr("No track points loaded."));
		return;
	}

	if (playing) {
		return;
	}

	playing = true;

	refineTrack();

	currentIndex = 0;

	// Create the marker and add it to the map
	QString js = "var marker = L.marker([0, 0]).addTo(map);";
	mapView->page()->runJavaScript(js);

	// Define a timer to update the map with the current track point every 500 milliseconds
	timer = new QTimer(this);
	connect(timer, &QTimer::timeout, this, [=]() {
		qDebug() << "currentIndex == " << currentIndex;
		if (currentIndex >= refinedTrackPoints.size() - 1) {
			timer->stop();
			QMessageBox::information(this, tr("Info"), tr("Playback finished."));
			playing = false;
			QString js = "map.removeLayer(marker);";
			mapView->page()->runJavaScript(js);
			return;
		}

		double lat1 = refinedTrackPoints[currentIndex].first;
		double lon1 = refinedTrackPoints[currentIndex].second;
		double lat2 = refinedTrackPoints[currentIndex + 1].first;
		double lon2 = refinedTrackPoints[currentIndex + 1].second;

		double distance = calculateDistance(lat1, lon1, lat2, lon2);
		qDebug() << "distance == " << distance;

		double interval = distance / (speedKmh / 3.6) * 1000; // in milliseconds
		qDebug() << "interval == " << interval;

		// Measure the time it takes to execute the JavaScript code
		QElapsedTimer elapsedTimer;
		elapsedTimer.start();

		QString js = QString("map.setView([%1, %2], 15);").arg(lat1).arg(lon1);
		js += QString("marker.setLatLng([%1, %2]);").arg(lat1).arg(lon1);
		js += "marker.update();";
		QApplication::processEvents(); // Allow Qt to update the UI
		mapView->page()->runJavaScript(js);

		// Subtract the elapsed time from the timer interval
		interval -= elapsedTimer.elapsed();

		sendIntent(lat1, lon1);

		currentIndex++;

		if (interval < 0) {
			interval = 0;
		}

		timer->setInterval(interval);
		});

	// Start the timer
	timer->start(0); // start with an interval of 0 to set the correct interval on the first iteration
}

double MainWindow::calculateDistance(double lat1, double lon1, double lat2, double lon2) {
	double R = 6371.0; // Earth's radius in km
	double dLat = qDegreesToRadians(lat2 - lat1);
	double dLon = qDegreesToRadians(lon2 - lon1);
	double a = qSin(dLat / 2) * qSin(dLat / 2) +
		qCos(qDegreesToRadians(lat1)) * qCos(qDegreesToRadians(lat2)) *
		qSin(dLon / 2) * qSin(dLon / 2);
	double c = 2 * qAtan2(qSqrt(a), qSqrt(1 - a));
	double distance = R * c * 1000; // meters
	qDebug() << "distance == " << distance;
	return distance;
}

void MainWindow::stopPlayGpxFile() {
	if (playing) {
		timer->stop();
		QMessageBox::information(this, tr("Info"), tr("Playback stopped."));
		playing = false;
		QString js = "map.removeLayer(marker);";
		mapView->page()->runJavaScript(js);
	}
}

void MainWindow::onSpeedChanged(int speedKmh)
{
	// Update the speed value and the label text when the slider value is changed
	this->speedKmh = speedKmh;
	speedLabel->setText(QString::number(speedKmh) + " km/h");
}

void MainWindow::setAdbPath() {
	QString fileName = QFileDialog::getOpenFileName(this, tr("Select ADB Path"), QDir::homePath(), tr("adb.exe (adb.exe)"));
	if (!fileName.isNull()) {
		// Save the selected file path to settings
		settings->setValue("adb_path", fileName);
		qDebug() << "file name" << fileName;
	}
}

void MainWindow::startAndroidApp() {
	QString adbCmd = "shell am start -n org.nitri.gpxplayer/.MainActivity";
	runAdbCommand(adbCmd);
}

void MainWindow::sendIntent(double latitude, double longitude) {
	
	QString adbCmd = "shell am broadcast -n org.nitri.gpxplayer/.MockLocationReceiver -a org.nitri.gpxplayer.ACTION_SET_LOCATION -d ";
	QString lat = QString::number(latitude, 'f', 6);
	QString lon = QString::number(longitude, 'f', 6);
	adbCmd += QString("geo:%1,%2").arg(lat).arg(lon);
	runAdbCommand(adbCmd);
}

void MainWindow::runAdbCommand(QString adbCmd) {
	// Build the adb command
	QString adb = "adb";
	QString adbPathSetting = settings->value("adb_path").toString();
	qDebug() << "file name" << adbPathSetting;
	if (!adbPathSetting.isEmpty()) {
		adb = adbPathSetting;
	}

	adbCmd = adb + " " + adbCmd;

	outputTextEdit->append(adbCmd);
	
	// Create a new QProcess object
	QProcess process;

	// Execute the adb command
	process.start(adbCmd);

	// Wait for the process to finish
	process.waitForFinished(-1);

	// Print the output to the console
	QString output = QString(process.readAllStandardError());
	output += QString(process.readAllStandardOutput());
	outputTextEdit->append(output);
	qDebug() << output;
}