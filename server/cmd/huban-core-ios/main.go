package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"sync"
	"unsafe"

	"github.com/UCKETX/HuyaPure/server/pkg/mobilecore"
)

var (
	errMu   sync.Mutex
	lastErr string
)

func setLastErr(err error) {
	errMu.Lock()
	defer errMu.Unlock()
	if err == nil {
		lastErr = ""
	} else {
		lastErr = err.Error()
	}
}

//export HubanStart
func HubanStart(dataDir *C.char, version *C.char) C.int {
	port, err := mobilecore.Start(C.GoString(dataDir), C.GoString(version))
	if err != nil {
		setLastErr(err)
		return -1
	}
	setLastErr(nil)
	return C.int(port)
}

//export HubanPort
func HubanPort() C.int {
	return C.int(mobilecore.Port())
}

//export HubanStop
func HubanStop() {
	mobilecore.Stop()
}

//export HubanRunAllSync
func HubanRunAllSync(trigger *C.char, onlyEnabled C.int) *C.char {
	res := mobilecore.RunAllSync(C.GoString(trigger), onlyEnabled != 0)
	return C.CString(res)
}

//export HubanLastError
func HubanLastError() *C.char {
	errMu.Lock()
	s := lastErr
	errMu.Unlock()
	return C.CString(s)
}

//export HubanFreeString
func HubanFreeString(s *C.char) {
	if s != nil {
		C.free(unsafe.Pointer(s))
	}
}

func main() {}
