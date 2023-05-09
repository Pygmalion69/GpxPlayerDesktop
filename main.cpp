#include <QApplication>
#include "mainwindow.h"

int main(int argc, char* argv[])
{
    QCoreApplication::setOrganizationName("nitri.org");
    QCoreApplication::setApplicationName("GpxPlayer");
    QApplication a(argc, argv);
    MainWindow w;
    w.resize(800, 840);
    w.setMaximumSize(1024, 840);
    w.show();
    return a.exec();
}
