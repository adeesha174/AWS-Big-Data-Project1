# AWS Big Data Project

This project was given to me as an assignment as part of a course during my bechelor dgree. In the course we used AWS and Hadoop to work with large amount of data. We learned the basics and the importance of ML.

In this assignment we coded a real-world application to distributively apply OCR algorithms on images. The result of this program displays each image with its recognized text on a webpage.

The application is composed of a local application and instances running on the Amazon cloud. The application will get as an input a text file containing a list of URLs of images. Then, instances will be launched in AWS (workers). Each worker will download image files, use some OCR library to identify text in those images (if any) and display the image with the text in a webpage.

The OCR tool we used is Tesseract (installed only on the workers).
