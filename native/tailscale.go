// CGo-обёртка над tsnet для использования из Java через JNA.
// Собирать: CGO_ENABLED=1 go build -buildmode=c-shared -o ../src/main/resources/natives/linux-amd64/libtailscale.so .

package main

/*
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
typedef uintptr_t Tailscale;
typedef uintptr_t TailscaleConn;
typedef uintptr_t TailscaleListener;
*/
import "C"
import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"sync"
	"time"
	"unsafe"

	"tailscale.com/tsnet"
)

func init() {
	// Направляем Go-логи в stderr чтобы они попали в лог Minecraft
	log.SetOutput(os.Stderr)
	log.SetPrefix("[tailmod-go] ")
}

// --- node registry ---

type nodeEntry struct {
	srv    *tsnet.Server
	errmsg string
}

type connEntry struct {
	conn   net.Conn
	errmsg string
}

type listenerEntry struct {
	ln     net.Listener
	errmsg string
}

var (
	mu          sync.Mutex
	nodes       = map[uintptr]*nodeEntry{}
	conns       = map[uintptr]*connEntry{}
	listeners   = map[uintptr]*listenerEntry{}
	nodeSeq     uintptr = 1
	connSeq     uintptr = 1
	listenerSeq uintptr = 1
)

func newNode(e *nodeEntry) C.Tailscale {
	mu.Lock()
	defer mu.Unlock()
	id := nodeSeq
	nodeSeq++
	nodes[id] = e
	return C.Tailscale(id)
}

func getNode(ts C.Tailscale) *nodeEntry {
	mu.Lock()
	defer mu.Unlock()
	return nodes[uintptr(ts)]
}

func newConn(e *connEntry) C.TailscaleConn {
	mu.Lock()
	defer mu.Unlock()
	id := connSeq
	connSeq++
	conns[id] = e
	return C.TailscaleConn(id)
}

func getConn(c C.TailscaleConn) *connEntry {
	mu.Lock()
	defer mu.Unlock()
	return conns[uintptr(c)]
}

// --- exported functions ---

//export TailscaleNew
func TailscaleNew() C.Tailscale {
	return newNode(&nodeEntry{srv: &tsnet.Server{}})
}

//export TailscaleSetDir
func TailscaleSetDir(ts C.Tailscale, dir *C.char) C.int {
	n := getNode(ts)
	if n == nil {
		return -1
	}
	n.srv.Dir = C.GoString(dir)
	return 0
}

//export TailscaleSetHostname
func TailscaleSetHostname(ts C.Tailscale, hostname *C.char) C.int {
	n := getNode(ts)
	if n == nil {
		return -1
	}
	n.srv.Hostname = C.GoString(hostname)
	return 0
}

//export TailscaleSetAuthKey
func TailscaleSetAuthKey(ts C.Tailscale, authKey *C.char) C.int {
	n := getNode(ts)
	if n == nil {
		return -1
	}
	n.srv.AuthKey = C.GoString(authKey)
	return 0
}

//export TailscaleSetEphemeral
func TailscaleSetEphemeral(ts C.Tailscale, ephemeral C.int) C.int {
	n := getNode(ts)
	if n == nil {
		return -1
	}
	n.srv.Ephemeral = ephemeral != 0
	return 0
}

//export TailscaleStart
func TailscaleStart(ts C.Tailscale) C.int {
	n := getNode(ts)
	if n == nil {
		return -1
	}
	log.Println("Starting tsnet server...")
	if err := n.srv.Start(); err != nil {
		n.errmsg = err.Error()
		log.Printf("TailscaleStart error: %v", err)
		return -1
	}
	log.Println("tsnet server started, waiting for connection...")
	return 0
}

//export TailscaleUp
func TailscaleUp(ts C.Tailscale) C.int {
	n := getNode(ts)
	if n == nil {
		return -1
	}
	// 300s — достаточно для авторизации через браузер
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Second)
	defer cancel()
	st, err := n.srv.Up(ctx)
	if err != nil {
		n.errmsg = fmt.Sprintf("Up failed: %v", err)
		log.Printf("TailscaleUp error: %v", err)
		return -1
	}
	log.Printf("TailscaleUp OK, IPs: %v", st.TailscaleIPs)
	return 0
}

// TailscaleGetAuthURL возвращает URL для авторизации через браузер.
// Возвращает 0 и записывает URL в buf если требуется авторизация.
// Возвращает 1 если авторизация не нужна (уже подключён).
// Возвращает -1 при ошибке.
//export TailscaleGetAuthURL
func TailscaleGetAuthURL(ts C.Tailscale, buf *C.char, bufLen C.int) C.int {
	n := getNode(ts)
	if n == nil {
		return -1
	}
	lc, err := n.srv.LocalClient()
	if err != nil {
		n.errmsg = err.Error()
		return -1
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		n.errmsg = err.Error()
		return -1
	}
	if st.AuthURL == "" {
		return 1 // уже авторизован
	}
	cStr := C.CString(st.AuthURL)
	defer C.free(unsafe.Pointer(cStr))
	C.strncpy(buf, cStr, C.size_t(bufLen-1))
	return 0
}

//export TailscaleClose
func TailscaleClose(ts C.Tailscale) C.int {
	mu.Lock()
	n := nodes[uintptr(ts)]
	delete(nodes, uintptr(ts))
	mu.Unlock()
	if n == nil {
		return -1
	}
	n.srv.Close()
	return 0
}

//export TailscaleIPv4
func TailscaleIPv4(ts C.Tailscale, buf *C.char, bufLen C.int) C.int {
	n := getNode(ts)
	if n == nil {
		return -1
	}
	ip4, _ := n.srv.TailscaleIPs()
	if !ip4.IsValid() {
		return -1
	}
	cStr := C.CString(ip4.String())
	defer C.free(unsafe.Pointer(cStr))
	C.strncpy(buf, cStr, C.size_t(bufLen-1))
	return 0
}

//export TailscaleIPv6
func TailscaleIPv6(ts C.Tailscale, buf *C.char, bufLen C.int) C.int {
	n := getNode(ts)
	if n == nil {
		return -1
	}
	_, ip6 := n.srv.TailscaleIPs()
	if !ip6.IsValid() {
		return -1
	}
	cStr := C.CString(ip6.String())
	defer C.free(unsafe.Pointer(cStr))
	C.strncpy(buf, cStr, C.size_t(bufLen-1))
	return 0
}

//export TailscaleDial
func TailscaleDial(ts C.Tailscale, network *C.char, addr *C.char) C.TailscaleConn {
	n := getNode(ts)
	if n == nil {
		return 0
	}
	conn, err := n.srv.Dial(context.Background(), C.GoString(network), C.GoString(addr))
	if err != nil {
		n.errmsg = err.Error()
		return 0
	}
	return newConn(&connEntry{conn: conn})
}

//export TailscaleConnRead
func TailscaleConnRead(c C.TailscaleConn, buf *C.char, bufLen C.int) C.int {
	e := getConn(c)
	if e == nil {
		return -1
	}
	goBuf := make([]byte, int(bufLen))
	n, err := e.conn.Read(goBuf)
	if n > 0 {
		C.memcpy(unsafe.Pointer(buf), unsafe.Pointer(&goBuf[0]), C.size_t(n))
	}
	if err == io.EOF {
		return 0
	}
	if err != nil {
		e.errmsg = err.Error()
		return -1
	}
	return C.int(n)
}

//export TailscaleConnWrite
func TailscaleConnWrite(c C.TailscaleConn, buf *C.char, bufLen C.int) C.int {
	e := getConn(c)
	if e == nil {
		return -1
	}
	goBuf := C.GoBytes(unsafe.Pointer(buf), bufLen)
	n, err := e.conn.Write(goBuf)
	if err != nil {
		e.errmsg = err.Error()
		return -1
	}
	return C.int(n)
}

//export TailscaleConnClose
func TailscaleConnClose(c C.TailscaleConn) C.int {
	mu.Lock()
	e := conns[uintptr(c)]
	delete(conns, uintptr(c))
	mu.Unlock()
	if e == nil {
		return -1
	}
	e.conn.Close()
	return 0
}

//export TailscaleStatus
func TailscaleStatus(ts C.Tailscale, buf *C.char, bufLen C.int) C.int {
	n := getNode(ts)
	if n == nil {
		return -1
	}
	lc, err := n.srv.LocalClient()
	if err != nil {
		n.errmsg = err.Error()
		return -1
	}
	st, err := lc.Status(context.Background())
	if err != nil {
		n.errmsg = err.Error()
		return -1
	}
	data, err := json.Marshal(st)
	if err != nil {
		n.errmsg = err.Error()
		return -1
	}
	if len(data)+1 > int(bufLen) {
		return -1
	}
	cStr := C.CString(string(data))
	defer C.free(unsafe.Pointer(cStr))
	C.strncpy(buf, cStr, C.size_t(bufLen-1))
	return 0
}

//export TailscaleErrmsg
func TailscaleErrmsg(ts C.Tailscale) *C.char {
	n := getNode(ts)
	if n == nil {
		return C.CString("invalid handle")
	}
	return C.CString(n.errmsg)
}

//export TailscaleListen
func TailscaleListen(ts C.Tailscale, network *C.char, addr *C.char) C.TailscaleListener {
	n := getNode(ts)
	if n == nil {
		return 0
	}
	ln, err := n.srv.Listen(C.GoString(network), C.GoString(addr))
	if err != nil {
		n.errmsg = err.Error()
		log.Printf("TailscaleListen error: %v", err)
		return 0
	}
	mu.Lock()
	defer mu.Unlock()
	id := listenerSeq
	listenerSeq++
	listeners[id] = &listenerEntry{ln: ln}
	return C.TailscaleListener(id)
}

// TailscaleAccept блокирует до нового входящего соединения.
// Возвращает TailscaleConn или 0 если listener закрыт.
//export TailscaleAccept
func TailscaleAccept(ln C.TailscaleListener) C.TailscaleConn {
	mu.Lock()
	e := listeners[uintptr(ln)]
	mu.Unlock()
	if e == nil {
		return 0
	}
	conn, err := e.ln.Accept()
	if err != nil {
		return 0
	}
	return newConn(&connEntry{conn: conn})
}

//export TailscaleListenerClose
func TailscaleListenerClose(ln C.TailscaleListener) C.int {
	mu.Lock()
	e := listeners[uintptr(ln)]
	delete(listeners, uintptr(ln))
	mu.Unlock()
	if e == nil {
		return -1
	}
	e.ln.Close()
	return 0
}

func main() {}
