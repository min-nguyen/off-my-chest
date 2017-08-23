


var ws = function(){
    url = $('#socket').attr('data-url')
    //Produced GET request to ws:localhost/chat/?id
    var connection = new WebSocket(url);
    var $send = $("#send"); 
    openConnection(url)
    function openConnection(url){
        var history_loaded = false;
        connection.onopen = function() {
            console.log("Connection succesful at " + url);

            $send.on('click', function() {
                connection.send($('#send-text').val());
                $('#send-text').val('');
            });

            connection.onmessage = function(event) {
                //Load History
                if(!history_loaded){
                    var history = loadHistory(event.data)
                    for(i = 0; i < history.length; i++){
                        $('#chatbox').append('<div>'+ history[i].user + ": " 
                                                    + history[i].text + '</div>')
                    }
                    history_loaded = true;
                }
                //Process real-time messages
                else{
                    console.log(event.data);
                    $('#chatbox').append('<div>' + event.data + '</div>')
                    $('#chatbox').stop ().animate ({
                        scrollTop: $('#chatbox')[0].scrollHeight
                    });
                }
            }
        };
        
    }
    function closeConnection(){
        if(connection != undefined){
            connection.send()
            connection.close()
        }
    }
    connection.onclose = function(){
        console.log("Connection closed")
    }
}

function loadHistory(event_data){
    try {
        var history = JSON.parse(event_data)
        return history
    } catch (e){
        return [];
    }
}