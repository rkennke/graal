#!/bin/bash

#rm -rf mxbuild ../sdk/mxbuild
#mx build

HOME=`mx graalvm-home`
cp $JAVA_HOME/bin/*.debuginfo ${HOME}/bin/
cp $JAVA_HOME/lib/*.debuginfo ${HOME}/lib/
cp $JAVA_HOME/lib/server/*.debuginfo ${HOME}/lib/server/
